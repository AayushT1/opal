/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package br
package instructions

import org.opalj.collection.immutable.Chain

/**
 * Enter monitor for object.
 *
 * @author Michael Eichberg
 */
case object MONITORENTER extends SynchronizationInstruction with InstructionMetaInformation {

    final val opcode = 194

    final val mnemonic = "monitorenter"

    final val jvmExceptions: List[ObjectType] = List(ObjectType.NullPointerException)

    final def stackSlotsChange: Int = -1

    final def nextInstructions(
        currentPC:             PC,
        regularSuccessorsOnly: Boolean
    )(
        implicit
        code:           Code,
        classHierarchy: ClassHierarchy = ClassHierarchy.PreInitializedClassHierarchy
    ): Chain[PC] = {
        if (regularSuccessorsOnly)
            Chain.singleton(indexOfNextInstruction(currentPC))
        else
            Instruction.nextInstructionOrExceptionHandler(
                this, currentPC, ObjectType.NullPointerException
            )
    }

}
