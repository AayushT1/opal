/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package br
package reader

import org.opalj.bi.reader.MethodParameters_attributeReader

/**
 * Implements the factory methods to create method parameter tables and their entries.
 *
 * @author Michael Eichberg
 */
trait MethodParameters_attributeBinding
    extends MethodParameters_attributeReader
    with ConstantPoolBinding
    with AttributeBinding {

    type MethodParameter = br.MethodParameter

    type MethodParameters_attribute = br.MethodParameterTable

    override def MethodParameters_attribute(
        cp:                   Constant_Pool,
        attribute_name_index: Constant_Pool_Index,
        parameters:           MethodParameters,
        as_name_index:        Constant_Pool_Index,
        as_descriptor_index:  Constant_Pool_Index
    ): MethodParameters_attribute = {
        new MethodParameterTable(parameters)
    }

    override def MethodParameter(
        cp:           Constant_Pool,
        name_index:   Constant_Pool_Index,
        access_flags: Int
    ): MethodParameter = {
        val parameterName =
            if (name_index == 0)
                None // it is a so-called formal parameter
            else
                Some(cp(name_index).asString)
        new MethodParameter(parameterName, access_flags)
    }
}
