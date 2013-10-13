/* License (BSD Style License):
 * Copyright (c) 2009 - 2013
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
 *  - Neither the name of the Software Technology Group or Technische
 *    Universität Darmstadt nor the names of its contributors may be used to
 *    endorse or promote products derived from this software without specific
 *    prior written permission.
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
package de.tud.cs.st
package bat
package resolved
package ai
package domain

import de.tud.cs.st.util.{ Answer, Yes, No, Unknown }

/**
 * Representation of some property.
 *
 * @author Michael Eichberg
 */
trait SimpleBooleanPropertyTracing[+I] extends PropertyTracing[I] { domain ⇒

    def propertyName: String

    // TODO Can I use an anyval over here?
    class BooleanProperty private[SimpleBooleanPropertyTracing] (
        val state: Boolean)
            extends Property {

        def merge(otherProperty: DomainProperty): Update[DomainProperty] = {
            val newState = this.state & otherProperty.state
            if (newState != this.state)
                StructuralUpdate(new BooleanProperty(newState))
            else
                NoUpdate
        }

        override def toString: String = domain.propertyName+"("+state+")"
    }

    def updateProperty(pc: Int, newState: Boolean) {
        propertiesArray(pc) = new BooleanProperty(newState)
    }

    final type DomainProperty = BooleanProperty

    final val DomainPropertyTag: reflect.ClassTag[DomainProperty] = implicitly

    def initialPropertyValue: DomainProperty = new BooleanProperty(false)

    def hasPropertyOnExit(returnedValues: Set[_ <: (_, PC, _)]): Boolean = {
        //println(
        // identifier+" "+
        // returnedValues.map { v ⇒ val (_, pc, _) = v; pc }.
        //   map(getProperty(_)).mkString(","))
        returnedValues.forall { v ⇒ val (_, pc, _) = v; getProperty(pc).state }
    }
}



