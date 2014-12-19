/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2014
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.opalj
package ai
package domain

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.FunSpec
import org.scalatest.Matchers
import java.net.URL
import scala.Console.BLUE
import scala.Console.BOLD
import scala.Console.CYAN
import scala.Console.RED
import scala.Console.RESET
import org.opalj.br.ClassFile
import org.opalj.br.Method
import org.opalj.br.analyses.Project
import org.opalj.ai.domain
import org.opalj.ai.project.CallGraph
import org.opalj.ai.project.CHACallGraphAlgorithmConfiguration
import org.opalj.ai.project.CallGraphFactory
import org.opalj.ai.project.CallGraphFactory.defaultEntryPointsForLibraries
import org.opalj.ai.project.ComputedCallGraph
import org.opalj.ai.project.VTACallGraphAlgorithmConfiguration
import org.opalj.ai.project.DefaultVTACallGraphDomain
import org.opalj.ai.project.ExtVTACallGraphDomain
import org.opalj.ai.project.BasicVTACallGraphDomain
import org.opalj.ai.project.UnresolvedMethodCall
import org.opalj.ai.project.CallGraphConstructionException
import org.opalj.ai.debug.{ CallGraphDifferenceReport, AdditionalCallTargets }
import org.opalj.ai.project.CallGraphCache
import org.opalj.ai.project.VTAWithPreAnalysisCallGraphAlgorithmConfiguration
import org.opalj.ai.project.BasicVTAWithPreAnalysisCallGraphDomain

/**
 * Compares the precision of different call graphs.
 *
 * @author Michael Eichberg
 */
@RunWith(classOf[JUnitRunner])
class CallGraphPrecisionTest extends FunSpec with Matchers {

    describe("call graphs") {
        info("loading the JRE")
        val project = org.opalj.br.TestSupport.createRTJarProject
        val entryPoints = CallGraphFactory.defaultEntryPointsForLibraries(project)
        info("loaded the JRE")

        describe("result of calculating a callgraph") {

            var CHACG: CallGraph = null
            var CHACGUnresolvedCalls: List[UnresolvedMethodCall] = null
            var CHACGCreationExceptions: List[CallGraphConstructionException] = null
            var VTACG: CallGraph = null

            it("calculating the CHA based call graph multiple times using the same project should create the same call graph") {
                info("calculating the CHA based call graph (1)")
                val ComputedCallGraph(theCHACG, unresolvedCalls, creationExceptions) =
                    CallGraphFactory.create(
                        project,
                        entryPoints,
                        new CHACallGraphAlgorithmConfiguration(project)
                    )
                CHACG = theCHACG
                CHACGUnresolvedCalls = unresolvedCalls
                CHACGCreationExceptions = creationExceptions

                info("calculating the CHA based call graph (2)")
                val ComputedCallGraph(newCHACG, _, _) =
                    CallGraphFactory.create(
                        project,
                        entryPoints,
                        new CHACallGraphAlgorithmConfiguration(project)
                    )

                info("comparing the call graphs")
                val (unexpected, additional) =
                    org.opalj.ai.debug.CallGraphComparison(project, theCHACG, newCHACG)
                unexpected should be(empty)
                additional should be(empty)
            }

            it("calculating the CHA based Call Graph for the same JAR multiple times should create the same call graph") {
                info("loading the JRE (again)")
                val newProject = org.opalj.br.TestSupport.createRTJarProject

                info("calculating the CHA based call graph using the new project")
                val ComputedCallGraph(newCHACG, newCHACGUnresolvedCalls, newCHACGCreationExceptions) =
                    CallGraphFactory.create(
                        newProject,
                        CallGraphFactory.defaultEntryPointsForLibraries(newProject),
                        new CHACallGraphAlgorithmConfiguration(newProject)
                    )

                info("comparing the call graphs")
                // we cannot compare the call graphs using the standard CallGraphComparison
                // functionality since the Method objects etc. are not comparable.
                CHACG.callsCount should be(newCHACG.callsCount)
                CHACG.calledByCount should be(newCHACG.calledByCount)
                CHACGUnresolvedCalls.size should be(newCHACGUnresolvedCalls.size)
                CHACGCreationExceptions.size should be(newCHACGCreationExceptions.size)

                val mutex = new Object
                var deviations = List.empty[String]

                for {
                    newClassFile ← newProject.classFiles.par
                    classFile = project.classFile(newClassFile.thisType).get
                    newMethodsIterator = newClassFile.methods.iterator
                    methodsIterator = classFile.methods.iterator
                } {
                    if (newClassFile.methods.size != classFile.methods.size) {
                        mutex.synchronized {
                            deviations =
                                s"the classfiles for type ${newClassFile.thisType} contain "+
                                    s"different methods: ${newClassFile.methods.map(_.toJava()).mkString("\n", ",\n", "\n")} vs. ${classFile.methods.map(_.toJava()).mkString("\n", ",\n", "\n")}" ::
                                    deviations
                        }
                    } else {
                        while (newMethodsIterator.hasNext) {
                            val newMethod = newMethodsIterator.next
                            val method = methodsIterator.next

                            if (newMethod.toJava != method.toJava) {
                                fail(s"the methods associated with the class ${classFile.thisType} differ")
                            }

                            val methodCalledBy = CHACG.calledBy(method)
                            val newMethodCalledBy = newCHACG.calledBy(newMethod)
                            if (methodCalledBy.size != newMethodCalledBy.size) {
                                val mcb = methodCalledBy
                                mutex.synchronized {
                                     deviations =
                                         s"the method ${classFile.thisType.toJava}{ ${method.toJava} } "+
                                             s"is not called by the same methods: $mcb vs. ${newMethodCalledBy} " ::
                                             deviations
                                }
                            }
                            CHACG.calls(method).size should be(newCHACG.calls(newMethod).size)
                        }
                    }
                }
                if (deviations.nonEmpty)
                    fail(deviations.mkString("\n"))
            }

            it("calculating the (Default) VTA based call graph multiple times for the same project should create the same call graph") {
                info("calculating the (Default) VTA based call graph (1)")

                val ComputedCallGraph(theVTACG, _, _) =
                    CallGraphFactory.create(
                        project,
                        entryPoints,
                        new VTAWithPreAnalysisCallGraphAlgorithmConfiguration(project) {
                            override def Domain[Source](
                                classFile: ClassFile,
                                method: Method): CallGraphDomain =
                                new DefaultVTACallGraphDomain(
                                    project,
                                    fieldValueInformation, methodReturnValueInformation,
                                    cache,
                                    classFile, method
                                )
                        })
                VTACG = theVTACG

                info("calculating the (Default) VTA based call graph (2)")
                val ComputedCallGraph(newVTACG, _, _) =
                    CallGraphFactory.create(
                        project,
                        entryPoints,
                        new VTAWithPreAnalysisCallGraphAlgorithmConfiguration(project) {
                            override def Domain[Source](
                                classFile: ClassFile,
                                method: Method): CallGraphDomain =
                                new DefaultVTACallGraphDomain(
                                    project,
                                    fieldValueInformation, methodReturnValueInformation,
                                    cache, classFile, method
                                )
                        })

                info("comparing the call graphs")
                val (unexpected, additional) =
                    org.opalj.ai.debug.CallGraphComparison(project, theVTACG, newVTACG)
                unexpected should be(empty)
                additional should be(empty)
            }

            it("the call graph created using CHA should be less precise than the one created using VTA") {
                val (unexpected, _/*additional*/) =
                    org.opalj.ai.debug.CallGraphComparison(project, CHACG, VTACG)
                if (unexpected.nonEmpty)
                    fail("the comparison of the CHA and the default VTA based call graphs failed:\n"+
                        unexpected.mkString("\n")+"\n")
            }
        }

        describe("the relation between different configurations of VTA callgraphs") {

            it("a VTA based call graph created using a less precise domain should be less precise than one created using a more precise domain") {

                info("calculating the basic VTA based call graph")
                val ComputedCallGraph(basicVTACG, _, _) =
                    CallGraphFactory.create(
                        project,
                        entryPoints,
                        new VTACallGraphAlgorithmConfiguration(project) {
                            override def Domain[Source](
                                classFile: ClassFile,
                                method: Method): CallGraphDomain =
                                new BasicVTACallGraphDomain(
                                    project, cache, classFile, method
                                )
                        })

                info("calculating the default VTA with pre analysis based call graph")
                val ComputedCallGraph(basicVTAWithPreAnalysisCG, _, _) =
                    CallGraphFactory.create(
                        project,
                        entryPoints,
                        new VTAWithPreAnalysisCallGraphAlgorithmConfiguration(project) {
                            override def Domain[Source](
                                classFile: ClassFile,
                                method: Method): CallGraphDomain =
                                new BasicVTAWithPreAnalysisCallGraphDomain(
                                    project,
                                    fieldValueInformation, methodReturnValueInformation,
                                    cache,
                                    classFile, method
                                )
                        })

                {
                    val (unexpected, _/* additional*/) =
                        org.opalj.ai.debug.CallGraphComparison(project, basicVTACG, basicVTAWithPreAnalysisCG)
                    if (unexpected.nonEmpty)
                        fail("the comparison of the basic VTA with the one using pre analyses failed:\n"+
                            unexpected.mkString("\n")+"\n")

                }

                info("calculating the default VTA based call graph")
                val ComputedCallGraph(defaultVTACG, _, _) =
                    CallGraphFactory.create(
                        project,
                        entryPoints,
                        new VTAWithPreAnalysisCallGraphAlgorithmConfiguration(project) {
                            override def Domain[Source](
                                classFile: ClassFile,
                                method: Method): CallGraphDomain =
                                new DefaultVTACallGraphDomain(
                                    project,
                                    fieldValueInformation, methodReturnValueInformation,
                                    cache,
                                    classFile, method
                                )
                        })

                {
                    val (unexpected,_ /*additional*/) =
                        org.opalj.ai.debug.CallGraphComparison(project, basicVTACG, defaultVTACG)
                    if (unexpected.nonEmpty)
                        fail("the comparison of the basic and the default VTA based call graphs failed:\n"+
                            unexpected.mkString("\n")+"\n")

                }

                info("calculating the more precise (Ext) VTA based call graph")
                val ComputedCallGraph(extVTACG, _, _) =
                    CallGraphFactory.create(
                        project,
                        entryPoints,
                        new VTAWithPreAnalysisCallGraphAlgorithmConfiguration(project) {
                            override def Domain[Source](
                                classFile: ClassFile,
                                method: Method): CallGraphDomain =
                                new ExtVTACallGraphDomain(
                                    project,
                                    fieldValueInformation, methodReturnValueInformation,
                                    cache,
                                    classFile, method
                                )
                        })

                info("comparing the variants of the VTA based call graphs")

                {
                    val (unexpected,_ /* additional*/) =
                        org.opalj.ai.debug.CallGraphComparison(project, defaultVTACG, extVTACG)
                    if (unexpected.nonEmpty)
                        fail("the comparison of the default and the ext VTA based call graphs failed:\n"+
                            unexpected.mkString("\n")+"\n")
                }

            }
        }
    }
}
