/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2017
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

import org.opalj.log.GlobalLogContext
import org.opalj.log.OPALLogger

/**
 * Implementation of a library for parsing Java bytecode and creating arbitrary
 * representations.
 *
 * OPAL's primary representation of Java byte code
 * is the [[org.opalj.br]] representation which is defined in the
 * respective package. A second representation that represents bytecode one-by-one
 * is found in the [[org.opalj.da]] package.
 *
 * == This Package ==
 * Common constants and type definitions used across OPAL.
 *
 * @author Michael Eichberg
 */
package object bi {

    {
        // Log the information whether a production build or a development build is
        // used.
        implicit val logContext = GlobalLogContext
        import OPALLogger.info
        try {
            scala.Predef.assert(false)
            info("OPAL", "Bytecode Infrastructure - Production Build")
        } catch {
            case ae: AssertionError ⇒
                info("OPAL", "Bytecode Infrastructure - Development Build (Assertions are enabled)")
        }
    }

    type AccessFlagsContext = AccessFlagsContexts.Value

    type AttributeParent = AttributesParent.Value

    type ConstantPoolTag = ConstantPoolTags.Value

    /**
     * Every Java class file starts with "0xCAFEBABE".
     */
    final val ClassFileMagic = 0xCAFEBABE

    /**
     * Returns a textual representation of the Java version used to create the respective
     * class file.
     */
    def jdkVersion(majorVersion: Int): String = {
        // 53 == 9, 52 == 8; ... 50 == 6
        if (majorVersion >= 49) {
            "Java "+(majorVersion - 44)
        } else if (majorVersion > 45) {
            "Java 2 Platform version 1."+(majorVersion - 44)
        } else {
            "JDK 1.1 (JDK 1.0.2)"
        }
    }

    final val Java9MajorVersion = 53
    final val Java8MajorVersion = 52
    final val Java1MajorVersion = 45

    /**
     * Returns `true` if the current JRE is at least Java 8 or a newer version.
     *
     * @note This method makes some assumptions how the version numbers will evolve.
     */
    final lazy val isCurrentJREAtLeastJava8: Boolean = {
        val versionString = System.getProperty("java.version")
        try {
            val splittedVersionString = versionString.split('.')
            if (Integer.parseInt(splittedVersionString(0)) > 1 /*up until Java 8, the first number is "1" */ ||
                (splittedVersionString.length > 1 && Integer.parseInt(splittedVersionString(1)) >= 8)) {

                OPALLogger.info("system configuration", s"current JRE is at least Java 8")(GlobalLogContext)
                true
            } else {
                OPALLogger.info("system configuration", s"current JRE is older than Java 8")(GlobalLogContext)
                false // we were not able to detect/derive enough information!
            }
        } catch {
            case t: Throwable ⇒
                OPALLogger.error(
                    "system configuration", s"could not interpret JRE version: $versionString"
                )(GlobalLogContext)
                false
        }
    }

}
