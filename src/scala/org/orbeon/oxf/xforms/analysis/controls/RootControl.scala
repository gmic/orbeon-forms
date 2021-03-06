/**
 *  Copyright (C) 2010 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.analysis.controls

import org.dom4j.Element
import org.orbeon.oxf.xforms.analysis.{ContainerChildrenBuilder, StaticStateContext, SimpleElementAnalysis}
import org.orbeon.oxf.xforms.xbl.Scope
import org.orbeon.oxf.xforms.XFormsConstants.XBL_XBL_QNAME

/**
 * Single root container for the entire controls hierarchy.
 */
class RootControl(staticStateContext: StaticStateContext, element: Element, scope: Scope)
    extends SimpleElementAnalysis(staticStateContext, element, None, None, scope)
    with ContainerChildrenBuilder {

    // Ignore xbl:xbl elements that can be at the top-level, as the static state document produced by the extractor
    // might place them there.
    override def findRelevantChildrenElements =
        super.findRelevantChildrenElements filterNot (_.getQName == XBL_XBL_QNAME)
}