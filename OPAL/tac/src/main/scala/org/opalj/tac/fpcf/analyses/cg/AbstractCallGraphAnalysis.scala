/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package cg

import org.opalj.log.OPALLogger.logOnce
import org.opalj.log.Warn
import org.opalj.collection.ForeachRefIterator
import org.opalj.collection.immutable.IntTrieSet
import org.opalj.fpcf.EPS
import org.opalj.fpcf.InterimPartialResult
import org.opalj.fpcf.ProperPropertyComputationResult
import org.opalj.fpcf.Results
import org.opalj.fpcf.SomeEPS
import org.opalj.fpcf.UBP
import org.opalj.value.IsMObjectValue
import org.opalj.value.IsNullValue
import org.opalj.value.IsSArrayValue
import org.opalj.value.IsSObjectValue
import org.opalj.br.DefinedMethod
import org.opalj.br.Method
import org.opalj.br.MethodDescriptor
import org.opalj.br.ObjectType
import org.opalj.br.ReferenceType
import org.opalj.br.analyses.cg.CallBySignatureKey
import org.opalj.br.analyses.cg.IsOverridableMethodKey
import org.opalj.collection.immutable.RefArray
import org.opalj.tac.fpcf.properties.TACAI

trait CGState extends TACAIBasedAnalysisState {

    def hasNonFinalCallSite: Boolean
}

/**
 * A base class for call graph analyses based on the FPCF framework.
 * It uses the AI information of the three-address code to get the most precise information for
 * virtual calls.
 * `handleImpreciseCall` will be invoked for each virtual call, that could not be resolved
 * precisely.
 *
 * @see [[org.opalj.tac.fpcf.analyses.cg.CHACallGraphAnalysis]] or
 *     [[org.opalj.tac.fpcf.analyses.cg.rta.RTACallGraphAnalysis]] for example analyses.
 *
 * @author Florian Kuebler
 */
trait AbstractCallGraphAnalysis extends ReachableMethodAnalysis {
    type State <: CGState
    type LocalTypeInformation

    private[this] val isMethodOverridable: Method ⇒ Answer = project.get(IsOverridableMethodKey)
    private[this] lazy val getCBSTargets = project.get(CallBySignatureKey)
    private[this] val resovleCallBySignature =
        project.config.getBoolean("org.opalj.br.analyses.cg.callBySignatureResolution")

    def createInitialState(definedMethod: DefinedMethod, tacEP: EPS[Method, TACAI]): State

    /**
     * Handles updates for the three-address code.
     * Subclasses that might have other dependencies must override this method and should call
     * `super.c(...)` for updates of other property kinds then the new one.
     *
     * @see [[org.opalj.tac.fpcf.analyses.cg.rta.RTACallGraphAnalysis.c]] for an example.
     */
    def c(state: State)(eps: SomeEPS): ProperPropertyComputationResult = eps match {
        case UBP(tacai: TACAI) if tacai.tac.isDefined ⇒
            state.updateTACDependee(eps.asInstanceOf[EPS[Method, TACAI]])

            // we only want to add the new calls, so we create a fresh object
            processMethod(state, new DirectCalls())

        case UBP(_: TACAI) ⇒
            throw new IllegalStateException("there was already a tac defined")
    }

    override final def processMethod(
        definedMethod: DefinedMethod, tacEP: EPS[Method, TACAI]
    ): ProperPropertyComputationResult = {
        val state = createInitialState(definedMethod, tacEP)
        processMethod(state, new DirectCalls())
    }

    protected[this] def doHandleVirtualCall(
        caller:                        DefinedMethod,
        call:                          Call[V] with VirtualCall[V],
        pc:                            Int,
        specializedDeclaringClassType: ReferenceType,
        potentialTargets:              ForeachRefIterator[ObjectType],
        isPrecise:                     Boolean,
        calleesAndCallers:             DirectCalls
    )(implicit state: State): Unit = {
        val callerType = caller.declaringClassType
        val callSite = CallSite(pc, call.name, call.descriptor, call.declaringClass)

        val cbsTargets = if (!isPrecise && resovleCallBySignature && call.isInterface & call.declaringClass.isObjectType) {
            val cf = project.classFile(call.declaringClass.asObjectType)
            cf.flatMap { _.findMethod(call.name, call.descriptor) }.map {
                getCBSTargets(_)
            }.getOrElse(RefArray.empty)
        } else RefArray.empty

        val targetTypes = potentialTargets ++ cbsTargets.foreachIterator

        var unresolvedTypes = IntTrieSet.empty

        val localTypeInformation = getLocalTypeInformation(callSite, call)

        for (possibleTgtType ← targetTypes) {
            if (canResolveCall(localTypeInformation, state)(possibleTgtType)) {
                val tgtR = project.instanceCall(
                    callerType, possibleTgtType, call.name, call.descriptor
                )

                handleCall(
                    caller,
                    call.name,
                    call.descriptor,
                    call.declaringClass,
                    pc,
                    tgtR,
                    calleesAndCallers
                )
            } else {
                unresolvedTypes += possibleTgtType.id
            }
        }

        if (unresolvedTypes.nonEmpty)
            handleUnresolvedCall(unresolvedTypes, callSite)

        // Deal with the fact that there may be unknown subtypes of the receiver type that might
        // override the method
        if (!isPrecise) {
            if (specializedDeclaringClassType.isObjectType) {
                val declType = specializedDeclaringClassType.asObjectType

                val mResult = if (classHierarchy.isInterface(declType).isYes)
                    org.opalj.Result(project.resolveInterfaceMethodReference(
                        declType, call.name, call.descriptor
                    ))
                else
                    org.opalj.Result(project.resolveMethodReference(
                        declType,
                        call.name,
                        call.descriptor,
                        forceLookupInSuperinterfacesOnFailure = true
                    ))

                if (mResult.isEmpty) {
                    unknownLibraryCall(
                        caller,
                        call.name,
                        call.descriptor,
                        call.declaringClass,
                        declType,
                        caller.definedMethod.classFile.thisType.packageName,
                        pc,
                        calleesAndCallers
                    )
                } else if (isMethodOverridable(mResult.value).isYesOrUnknown) {
                    calleesAndCallers.addIncompleteCallSite(pc)
                }
            }
        }
    }

    protected final def processMethod(
        state: State, calls: DirectCalls
    ): ProperPropertyComputationResult = {
        val tac = state.tac

        tac.stmts.foreach {
            case stmt @ StaticFunctionCallStatement(call) ⇒
                handleCall(
                    state.method,
                    call.name,
                    call.descriptor,
                    call.declaringClass,
                    stmt.pc,
                    call.resolveCallTarget(state.method.declaringClassType),
                    calls
                )

            case call: StaticMethodCall[V] ⇒
                handleCall(
                    state.method,
                    call.name,
                    call.descriptor,
                    call.declaringClass,
                    call.pc,
                    call.resolveCallTarget(state.method.declaringClassType),
                    calls
                )

            case stmt @ NonVirtualFunctionCallStatement(call) ⇒
                handleCall(
                    state.method,
                    call.name,
                    call.descriptor,
                    call.declaringClass,
                    stmt.pc,
                    call.resolveCallTarget(state.method.declaringClassType),
                    calls
                )

            case call: NonVirtualMethodCall[V] ⇒
                handleCall(
                    state.method,
                    call.name,
                    call.descriptor,
                    call.declaringClass,
                    call.pc,
                    call.resolveCallTarget(state.method.declaringClassType),
                    calls
                )

            case VirtualFunctionCallStatement(call) ⇒
                handleVirtualCall(state.method, call, call.pc, calls)(state)

            case call: VirtualMethodCall[V] ⇒
                handleVirtualCall(state.method, call, call.pc, calls)(state)

            case Assignment(_, _, idc: InvokedynamicFunctionCall[V]) ⇒
                calls.addIncompleteCallSite(idc.pc)
                logOnce(
                    Warn("analysis - call graph construction", s"unresolved invokedynamic: $idc")
                )

            case ExprStmt(_, idc: InvokedynamicFunctionCall[V]) ⇒
                calls.addIncompleteCallSite(idc.pc)
                logOnce(
                    Warn("analysis - call graph construction", s"unresolved invokedynamic: $idc")
                )

            case idc: InvokedynamicMethodCall[_] ⇒
                calls.addIncompleteCallSite(idc.pc)
                logOnce(
                    Warn("analysis - call graph construction", s"unresolved invokedynamic: $idc")
                )

            case _ ⇒ //nothing to do
        }

        returnResult(calls)(state)
    }

    protected[this] def returnResult(
        calleesAndCallers: DirectCalls
    )(implicit state: State): ProperPropertyComputationResult = {
        val results = calleesAndCallers.partialResults(state.method)

        // FIXME: This won't work for refinable TACs as state.hasNonFinalCallSite may return false
        //  even if an update for the tac might add a non-final call site
        if (state.hasNonFinalCallSite && state.hasOpenDependencies)
            Results(
                InterimPartialResult(state.dependees, c(state)),
                results
            )
        else
            Results(results)
    }

    protected final def handleCall(
        caller:             DefinedMethod,
        callName:           String,
        callDescriptor:     MethodDescriptor,
        callDeclaringClass: ReferenceType,
        pc:                 Int,
        target:             org.opalj.Result[Method],
        calleesAndCallers:  DirectCalls
    ): Unit = {
        if (target.hasValue) {
            val tgtDM = declaredMethods(target.value)
            calleesAndCallers.addCall(caller, tgtDM, pc)
        } else {
            val packageName = caller.definedMethod.classFile.thisType.packageName
            unknownLibraryCall(
                caller,
                callName,
                callDescriptor,
                callDeclaringClass,
                callDeclaringClass,
                packageName,
                pc,
                calleesAndCallers
            )
        }
    }

    protected final def unknownLibraryCall(
        caller:              DefinedMethod,
        callName:            String,
        callDescriptor:      MethodDescriptor,
        callDeclaringClass:  ReferenceType,
        runtimeReceiverType: ReferenceType,
        packageName:         String,
        pc:                  Int,
        calleesAndCallers:   DirectCalls
    ): Unit = {
        val declaringClassType = callDeclaringClass.mostPreciseObjectType
        val runtimeType = runtimeReceiverType.mostPreciseObjectType

        val declTgt = declaredMethods.apply(
            declaringClassType,
            packageName,
            runtimeType,
            callName,
            callDescriptor
        )

        if (declTgt.isVirtualOrHasSingleDefinedMethod) {
            calleesAndCallers.addCall(caller, declTgt, pc)
        } else {
            declTgt.definedMethods foreach { m ⇒
                val dm = declaredMethods(m)
                calleesAndCallers.addCall(caller, dm, pc)
            }
        }

        calleesAndCallers.addIncompleteCallSite(pc)
    }

    /**
     * Computes the calles of the given `method` including the known effect of the `call` and
     * the call sites associated ith this call (in order to process updates of instantiated types).
     * There can be multiple "call sites", in case the three-address code has computed multiple
     * type bounds for the receiver.
     */
    private[this] def handleVirtualCall(
        caller:            DefinedMethod,
        call:              Call[V] with VirtualCall[V],
        pc:                Int,
        calleesAndCallers: DirectCalls
    )(implicit state: State): Unit = {
        val rvs = call.receiver.asVar.value.asReferenceValue.allValues
        for (rv ← rvs) rv match {
            case _: IsSArrayValue ⇒
                handlePreciseCall(ObjectType.Object, caller, call, pc, calleesAndCallers)

            case ov: IsSObjectValue ⇒
                if (ov.isPrecise) {
                    handlePreciseCall(ov.theUpperTypeBound, caller, call, pc, calleesAndCallers)
                } else {
                    handleImpreciseCall(ov.theUpperTypeBound, caller, call, pc, calleesAndCallers)
                }

            case mv: IsMObjectValue ⇒
                val typeBounds = mv.upperTypeBound
                val remainingTypeBounds = typeBounds.tail
                val firstTypeBound = typeBounds.head
                val potentialTypes = ch.allSubtypesForeachIterator(
                    firstTypeBound, reflexive = true
                ).filter { subtype ⇒
                    val cfOption = project.classFile(subtype)
                    cfOption.isDefined && {
                        val cf = cfOption.get
                        !cf.isInterfaceDeclaration && !cf.isAbstract &&
                            remainingTypeBounds.forall { supertype ⇒
                                ch.isSubtypeOf(subtype, supertype)
                            }
                    }
                }

                doHandleVirtualCall(
                    caller,
                    call,
                    pc,
                    call.declaringClass,
                    potentialTypes,
                    isPrecise = false,
                    calleesAndCallers
                )

            case _: IsNullValue ⇒
            // TODO: do not ignore the implicit calls to NullPointerException.<init>
        }
    }

    protected[this] def handlePreciseCall(
        calleeType:        ObjectType,
        caller:            DefinedMethod,
        call:              Call[V] with VirtualCall[V],
        pc:                Int,
        calleesAndCallers: DirectCalls
    )(implicit state: State): Unit = {
        doHandleVirtualCall(
            caller,
            call,
            pc,
            calleeType,
            ForeachRefIterator(calleeType),
            isPrecise = true,
            calleesAndCallers
        )
    }

    protected[this] def handleImpreciseCall(
        calleeType:        ObjectType,
        caller:            DefinedMethod,
        call:              Call[V] with VirtualCall[V],
        pc:                Int,
        calleesAndCallers: DirectCalls
    )(implicit state: State): Unit = {
        val potentialTypes = classHierarchy.allSubtypesForeachIterator(
            calleeType, reflexive = true
        ).filter { subtype ⇒
            val cfOption = project.classFile(subtype)
            cfOption.isDefined && {
                val cf = cfOption.get
                !cf.isInterfaceDeclaration && !cf.isAbstract
            }
        }

        doHandleVirtualCall(
            caller,
            call,
            pc,
            calleeType,
            potentialTypes,
            isPrecise = false,
            calleesAndCallers
        )
    }

    /**
     * Decides whether this call graph implementation can resolve this call immediately.
     */
    @inline protected[this] def canResolveCall(
        localTypeInformation: LocalTypeInformation,
        state:                State
    ): ObjectType ⇒ Boolean

    /**
     * Handles a call that is not immediately resolved by this call graph implementation.
     */
    @inline protected[this] def handleUnresolvedCall(
        unresovedTypes: IntTrieSet,
        callSite:       CallSite
    )(implicit state: State): Unit

    @inline protected[this] def getLocalTypeInformation(
        callSite: CallSite, call: Call[V] with VirtualCall[V]
    )(implicit state: State): LocalTypeInformation
}
