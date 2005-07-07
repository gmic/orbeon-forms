/**
 *  Copyright (C) 2005 Orbeon, Inc.
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
package org.orbeon.oxf.processor.converter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.pipeline.api.ExternalContext.Response;
import org.orbeon.oxf.portlet.PortletExternalContext;
import org.orbeon.oxf.processor.ProcessorImpl;
import org.orbeon.oxf.processor.ProcessorInputOutputInfo;
import org.orbeon.oxf.processor.ProcessorOutput;
import org.orbeon.oxf.xml.XMLUtils;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * <!-- XHTMLRewrite -->
 * XHTML specific Java impl of oxf-rewrite.xsl.  Uses GOF state pattern + SAX to get the job done.
 * The state machine ad hoc and relies a bit on the simplicity of the transformation that we are
 * perfoming. 
 * 
 * Wrt the transformation, here is the comment from oxf-rewrite.xsl :
 *   This stylesheet rewrites HTML or XHTML for servlets and portlets. URLs are parsed, so it must 
 *   be made certain that the URLs are well-formed. Absolute URLs are not modified. Relative or 
 *   absolute paths are supported, as well as the special case of a URL starting with a query string 
 *   (e.g. "?name=value"). This last syntax is supported by most Web browsers.
 *   
 *   A. For portlets, it does the following:
 *   1. Rewrite form/@action to WSRP action URL encoding
 *   2. Rewrite a/@href and link/@href to WSRP render encoding
 *   3. Rewrite img/@src, input[@type='image']/@src and script/@src to WSRP resource URL encoding
 *   4. If no form/@method is supplied, force an HTTP POST
 *   5. Escape any wsrp_rewrite occurence in text not within a script or
 *      SCRIPT element to wsrp_rewritewsrp_rewrite. WSRP 1.0 does not appear to
 *      specify a particular escape sequence, but we use this one in PresentationServer Portal. The
 *      escaped sequence is recognized by the PresentationServer Portlet and restored to the 
 *      original sequence, so it is possible to include the string wsrp_rewrite within documents.
 *   6. Occurrences of wsrp_rewrite found within script or SCRIPT elements, as well as occurrences 
 *      within attributes, are left untouched. This allows them to be recognized by the 
 *      PresentationServer Portlet and rewritten.
 *      
 *   Known issues for portlets:
 *   
 *   o The input document should not contain;
 *   o elements and attribute containing wsrp_rewrite
 *   o namespace URIs containing wsrp_rewrite
 *   o processing instructions containing wsrp_rewrite
 *   
 *   B. For servlets, it resrites the URLs to be absolute paths, and prepends the context path.
 *   
 *   @author d
 */
public class XHTMLRewrite extends ProcessorImpl {
	/**
	 * <!-- REWRITE_IN -->
	 * Name of the input that receives the content that is to be rewritten.
	 * @author d
	 */
	private static final String REWRITE_IN = "rewrite-in";
    /**
     * <!-- FORMATTING_URI -->
     * What you think.
     * @author d
     */
    static final String FORMATTING_URI = "http://orbeon.org/oxf/xml/formatting";
    /**
     * <!-- ACTION_ATT -->
     * What you think.
     * @author d
     */
    static final String ACTION_ATT = "action";
    /**
     * <!-- METHOD_ATT -->
     * What you think.
     * @author d
     */
    static final String METHOD_ATT = "method";
    /**
     * <!-- HREF_ATT -->
     * What you think.
     * @author d
     */
    static final String HREF_ATT = "href";
    /**
     * <!-- SRC_ATT -->
     * What you think.
     * @author d
     */
    static final String SRC_ATT = "src";
    /**
     * <!-- BACKGROUND_ATT -->
     * What you think.
     * @author d
     */
    static final String BACKGROUND_ATT = "background";
    /**
     * <!-- State -->
     * Base state.  Simply forwards data to the destination content handler and returns itself.
     * That is unless the ( element ) depth becomes negative after an end element event.  In this
     * case the previous state is returned.  This means btw that we are really only considering
     * state changes on start and end element events.
     * @author d
     */
    protected static abstract class State {
        /**
         * <!-- contentHandler -->
         * The destination of the rewrite transformation.
         * @author d
         */
        protected final ContentHandler contentHandler;
        /**
         * <!-- State -->
         * What you think.
         * @author d
         */
        protected final State previous;
        /**
         * <!-- response -->
         * Performs the URL rewrites.
         * @author d 
         */
        protected final Response response;
        /**
         * <!-- isPortlet -->
         * A sub-state, if you will.  Didn't implement this as a sub-class of State as it doesn't
         * change during the course of a transformation.
         * @see XHTMLRewrite
         * @author d 
         */
        protected final boolean isPortlet;
        /**
         * <!-- haveScriptAncestor -->
         * Could have been another State.  However since the value is determined in one state and
         * then used by a 'descendent' state doing so would have meant that descendent would have
         * to walk it's ancestors to get the value.  So, since making this a field instead of
         * a separate State sub-class was easier to implement and is faster a field was used.
         * @see XHTMLRewrite  
         * @author d 
         */
        protected final boolean haveScriptAncestor;
        /**
         * <!-- depth -->
         * At the moment are state transitions only happen on start element and end element events.
         * Therefore we track element depth and by default when the depth becomes negative we switch
         * to the previous state. 
         * @author d 
         */
        protected int depth = 0;
        /**
         * <!-- State -->
         * @param stt           The previous state.
         * @param cntntHndlr    The destination for the rewrite transformation.
         * @param rspns         Used to perform URL rewrites.
         * @param isPrtlt       Whether or not the context is a portlet context.
         * @param scrptAncstr   Whether content ( SAX events ) processed by this state are a 
         *                      descendent of a script element.
         * @see #previous
         * @see #contentHandler
         * @see #response
         * @see #isPortlet
         * @see #haveScriptAncestor
         * @author d
         */
        State( final State stt, final ContentHandler cntntHndlr, final Response rspns
               , final boolean isPrtlt, final boolean scrptAncstr ) {
            previous = stt;
            contentHandler = cntntHndlr;
            response = rspns;
            isPortlet = isPrtlt;
            haveScriptAncestor = scrptAncstr;
        }
        /**
         * <!-- characters -->
         * @see State
         * @author d
         */        
        State characters( final char[] ch, final int strt, final int len ) 
        throws SAXException {
            contentHandler.characters( ch, strt, len );
            return this;
        }
        /**
         * <!-- endDocument -->
         * @see State
         * @author d
         */        
        State endDocument() throws SAXException {
            contentHandler.endDocument();
            return this;
        }
        /**
         * <!-- endElement -->
         * @see State
         * @author d
         */        
        State endElement( final String ns, final String lnam, final String qnam ) 
        throws SAXException {
            depth--;
            final State ret;
            if ( depth >= 0 || previous == null ) {
                contentHandler.endElement( ns, lnam, qnam );
                ret = this;
            } else {
                ret = previous.endElement( ns, lnam, qnam );
            }
            return ret;
        }
        /**
         * <!-- endPrefixMapping-->
         * @see State
         * @author d
         */        
        State endPrefixMapping( final String pfx ) throws SAXException {
            contentHandler.endPrefixMapping( pfx );
            return this;
        }
        /**
         * <!-- ignorableWhitespace -->
         * @see State
         * @author d
         */        
        State ignorableWhitespace( final char[] ch, final int strt, final int len ) 
        throws SAXException {
            contentHandler.ignorableWhitespace( ch, strt, len );
            return this;
        }
        /**
         * <!-- processingInstructions -->
         * @see State
         * @author d
         */        
        State processingInstruction( final String trgt, final String dat ) 
        throws SAXException {
            contentHandler.processingInstruction( trgt, dat );
            return this;
        }
        /**
         * <!-- setDocumentLocator -->
         * @see State
         * @author d
         */        
        State setDocumentLocator( final Locator lctr ) {
            contentHandler.setDocumentLocator( lctr );
            return this;
        }
        /**
         * <!-- skippedEntity -->
         * @see State
         * @author d
         */        
        State skippedEntity( final String nam ) throws SAXException {
            contentHandler.skippedEntity( nam );
            return this;
        }
        /**
         * <!-- startDocument -->
         * @see State
         * @author d
         */        
        State startDocument() throws SAXException {
            contentHandler.startDocument();
            return this;
        }
        /**
         * <!-- startElement -->
         * @see State
         * @author d
         */        
        State startElement
        ( final String ns, final String lnam, final String qnam, final Attributes atts ) 
        throws SAXException {
            depth++;
            contentHandler.startElement( ns, lnam, qnam, atts );
            return this;
        }
        /**
         * <!-- startPrefixMapping -->
         * @see State
         * @author d
         */        
        State startPrefixMapping( final String pfx, final String uri ) throws SAXException {
            contentHandler.startPrefixMapping( pfx, uri );
            return this;
        }
    }
    /**
     * <!-- RootFilter -->
     * Ignores everything before start element.  On startElement switches to RewriteState.
     * So if this is used as the initial state then the result is that the prologue and epilogue
     * are ignored while the root element is passed to the next state.
     * @author d
     */
    static class RootFilter extends State {
        /**
         * <!-- RootFilter -->
         * Simple calls super(...)
         * @see State#State(State, ContentHandler, Response, boolean, boolean)
         * @author d
         */
        RootFilter( final State stt, final ContentHandler cntntHnder, final Response rspns
                    , final boolean isPrtlt, final boolean scrptAncstr ) {
            super( stt, cntntHnder, rspns, isPrtlt, scrptAncstr );
        }
        /**
         * <!-- startElement -->
         * @return new RewriteState( ... )
         * @see RewriteState
         * @author d
         */
        State startElement
        ( final String ns, final String lnam, final String qnam, final Attributes atts ) 
        throws SAXException {
            final State ret = new RewriteState
                ( this, contentHandler, response, isPortlet, haveScriptAncestor );
            return ret.startElement( ns, lnam, qnam, atts );
        }
        /**
         * <!-- characters -->
         * @return this.  Does nothing else.
         */
        State characters( final char[] ch, final int strt, final int len ) throws SAXException {
            return this;
        }
        /**
         * <!-- ignorableWhitespace -->
         * @return this.  Does nothing else.
         */
        State ignorableWhitespace( final char[] ch, final int strt, final int len ) 
        throws SAXException {
            return this;
        }
        /**
         * <!-- processingInstruction -->
         * @return this.  Does nothing else.
         */
        State processingInstruction( final String trgt, final String dat ) throws SAXException {
            return this;
        }
    }
    /**
     * <!-- RewriteState -->
     * The rewrite state.  Essentially this corresponds to the default mode of oxf-rewrite.xsl.
     * Basically this :
     * <ul>
     *   <li>Rewrites attribs in start element event when need be.
     *   <li>Accumulates text from characters events so that proper char content rewriting can
     *       happen.
     *   <li>On an event that indicates the end of potentially rewritable text, e.g. start element,
     *       rewrites and forwards the accumlated characters.
     *   <li>When explicit no write is indicated, e.g. we see attrib no-rewrite=true, then 
     *       transition to the NoRewriteState.
     * </ul>
     * @author dsmall
     */
    static class RewriteState extends State {
        /**
         * <!-- WSRP_REWRITE_REPLACMENT_CHARS -->
         * Chars useD to replace "wsrp_rewrite" in text.
         * @see XHTMLRewrite
         * @see RewriteState
         * @see #flushCharacters()
         * @author d
         */
        private static final char[] WSRP_REWRITE_REPLACMENT_CHARS = new char[] 
        { 'w', 's', 'r', 'p', '_', 'r', 'e', 'w', 'r', 'i', 't', 'e', 'w', 's', 'r', 'p', '_', 'r'
          , 'e', 'w', 'r', 'i', 't', 'e', };
        /**
         * <!-- wsrprewriteMatcher -->
         * Used to find wsrp_rewrite in char data.
         * @see RewriteState
         * @author d 
         */
        private final Matcher wsrprewriteMatcher;
        /**
         * <!-- charactersBuf -->
         * Used to accumlate characters from characters event.  Lazily init'd in characters.
         * Btw we use CharacterBuffer instead of StringBuffer because :
         * <ul>
         *   <li>We want something that works in JDK 1.4.</li>
         *   <li>
         *       We don't want the performance penalty of StringBuffer's synchronization in JDK 1.5.
         *   <li>
         * </ul>
         * 
         * Btw if we didn't care about 1.4 we could use StringBuilder instead.
         *   
         * @see RewriteState
         * @see #characters(char[], int, int)
         * @see #flushCharacters()
         * @author d 
         */
        private java.nio.CharBuffer charactersBuf;
        /**
         * <!-- RewriteState -->
         * Calls super( ... ) and initializes wsrprewriteMatcher with "wsrp_rewrite" 
         * @author d
         * @see State#State(State, ContentHandler, Response, boolean, boolean)
         */
        RewriteState( final State stt, final ContentHandler cntntHndlr, final Response rspns
                      , final boolean isPrtlt, final boolean scrptAncstr ) {
            super( stt, cntntHndlr, rspns, isPrtlt, scrptAncstr );
            final Pattern ptrn = Pattern.compile( "wsrp_rewrite" );
            wsrprewriteMatcher = ptrn.matcher( "" );
        }
        /**
         * <!-- handleEltWithResource -->
         * Handler for {http://www.w3.org/1999/xhtml}{elt name}.  Assumes namespace test has already 
         * happened.  Implements : 
         * <pre>
         *   <xsl:template match="xhtml:{elt name}[@{res attrib name}]" >
         *     <xsl:copy>
         *       <xsl:copy-of select="@*[namespace-uri() = '']"/>
         *         <xsl:attribute name="{res attrib name}">
         *           <xsl:value-of select="context:rewriteActionURL(@{res attrib name})"/>
         *         </xsl:attribute>
         *         <xsl:apply-templates/>
         *       </xsl:copy>
         *   </xsl:template>
         * </pre>
         * 
         * If match is satisfied then modified event is sent to destination contentHandler.
         * 
         * @return null if match is not satisfied and this otherwise.
         * @throws SAXException if destination contentHandler throws SAXException
         * @see XHTMLRewrite
         * @author d
         */
        private State handleEltWithResource
        ( final String elt, final String resAtt, final String ns, final String lnam
          , final String qnam, final Attributes atts ) 
        throws SAXException {
            State ret = null;
            done : if ( elt.equals( lnam ) ) {

                final String res = atts.getValue( "", resAtt );
                if ( res == null ) break done;

                ret = this;
                final AttributesImpl newAtts = XMLUtils.getAttribsFromDefaultNamespace( atts );
                final String newHref = response.rewriteResourceURL( res, false );
                newAtts.addAttribute( "", resAtt, resAtt, "", newHref );
                contentHandler.startElement( ns, lnam, qnam, newAtts  );
            } 
            return ret;
        }
        /**
         * <!-- handleA -->
         * Handler for {http://www.w3.org/1999/xhtml}a.  Assumes namespace test has already 
         * happened.  Implements : 
         * <pre>
         *   <xsl:template match="xhtml:a[@href]" >
         *     <xsl:copy>
         *       <xsl:copy-of select="@*[namespace-uri() = '']"/>
         *         <xsl:attribute name="href">
         *           <xsl:choose>
         *             <xsl:when test="not(@f:url-type) or @f:url-type = 'render'">
         *               <xsl:value-of select="context:rewriteRenderURL(@href)"/>
         *             </xsl:when>
         *             <xsl:when test="@f:url-type = 'action'">
         *               <xsl:value-of select="context:rewriteActionURL(@href)"/>
         *             </xsl:when>
         *             <xsl:when test="@f:url-type = 'resource'">
         *               <xsl:value-of select="context:rewriteResourceURL(@href)"/>
         *             </xsl:when>
         *           </xsl:choose>
         *         </xsl:attribute>
         *       <xsl:apply-templates/>
         *     </xsl:copy>
         *   </xsl:template>
         * </pre>
         * 
         * If match is satisfied then modified event is sent to destination contentHandler.

         * @return null if match is not satisfied and this otherwise.
         * @throws SAXException if destination contentHandler throws SAXException
         * @see XHTMLRewrite
         * @author d
         */
        private State handleA
        ( final String ns, final String lnam, final String qnam, final Attributes atts ) 
        throws SAXException {
            State ret = null;
            done : if ( "a".equals( lnam ) ) {

                final String href = atts.getValue( "", HREF_ATT );
                if ( href == null ) break done;

                ret = this;
                final AttributesImpl newAtts = XMLUtils.getAttribsFromDefaultNamespace( atts );
                final String url_typ = atts.getValue( FORMATTING_URI, "url-type" );

                final String newHref;
                if ( url_typ == null || "render".equals( url_typ ) ) {
                    newHref = response.rewriteRenderURL( href );
                } else if ( "action".equals( url_typ ) ) {
                    newHref = response.rewriteActionURL( href );
                } else if ( "resource".equals( url_typ ) ) {
                    newHref = response.rewriteResourceURL( href, false );
                } else {
                    newHref = null;
                }
                if ( newHref != null ) {
                    newAtts.addAttribute( "", HREF_ATT, HREF_ATT, "", newHref );
                }
                contentHandler.startElement( ns, lnam, qnam, newAtts  );
            } 
            return ret;
        }
        /**
         * <!-- handleArea -->
         * Handler for {http://www.w3.org/1999/xhtml}area.  Assumes namespace test has already 
         * happened.  Implements : 
         * <pre>
         *   <xsl:template match="xhtml:area[@href]" >
         *     <xsl:copy>
         *       <xsl:copy-of select="@*[namespace-uri() = '']"/>
         *         <xsl:attribute name="href">
         *           <xsl:value-of select="context:rewriteActionURL(@href)"/>
         *         </xsl:attribute>
         *         <xsl:apply-templates/>
         *       </xsl:copy>
         *   </xsl:template>
         * </pre>
         * 
         * If match is satisfied then modified event is sent to destination contentHandler.

         * @return null if match is not satisfied and this otherwise.
         * @throws SAXException if destination contentHandler throws SAXException
         * @see XHTMLRewrite
         * @author d
         */
        private State handleArea
        ( final String ns, final String lnam, final String qnam, final Attributes atts )
        throws SAXException {
            State ret = null;
            done : if ( "area".equals( lnam ) ) {

                final String href = atts.getValue( "", HREF_ATT );
                if ( href == null ) break done;

                ret = this;
                final AttributesImpl newAtts = XMLUtils.getAttribsFromDefaultNamespace( atts );
                final String newHref = response.rewriteActionURL( href );
                newAtts.addAttribute( "", HREF_ATT, HREF_ATT, "", newHref );
                contentHandler.startElement( ns, lnam, qnam, newAtts  );
            }
            return ret;
        }        
        /**
         * <!-- handleScript -->
         * Handler for {http://www.w3.org/1999/xhtml}script.  Assumes namespace test has already 
         * happened.  Implements : 
         * <pre>
         *   <xsl:template match="xhtml:script[@src]" >
         *     <xsl:copy>
         *       <xsl:copy-of select="@*[namespace-uri() = '']"/>
         *         <xsl:attribute name="src">
         *           <xsl:value-of select="context:rewriteActionURL(@src)"/>
         *         </xsl:attribute>
         *         <xsl:apply-templates/>
         *       </xsl:copy>
         *   </xsl:template>
         * </pre>
         * 
         * If match is satisfied then modified event is sent to destination contentHandler.
         * 
         * @return If handleEltWithResource( "script", "src", ... ) returns non-null then returns
         *         new RewriteState( this, contentHandler, response, isPortlet, true ) else returns
         *         null.  The final boolean conveys the fact that there is a script ancestor.
         * @throws SAXException if destination contentHandler throws SAXException
         * @see XHTMLRewrite
         * @see State#State(State, ContentHandler, Response, boolean, boolean)
         * @see State#haveScriptAncestor
         * @author d
         */
        private State handleScript
        ( final String ns, final String lnam, final String qnam, final Attributes atts ) 
        throws SAXException {
            final State stt = handleEltWithResource( "script", SRC_ATT, ns, lnam, qnam, atts );
            final State ret;
            if ( stt == null ) {
                ret = null;
            } else {
                ret = new RewriteState( this, contentHandler, response, isPortlet, true );
            }
            return ret;
        }
        /**
         * <!-- handleInput -->
         * Handler for {http://www.w3.org/1999/xhtml}input.  Assumes namespace test has already 
         * happened.  Implements : 
         * <pre>
         *   <xsl:template match="xhtml:input[@type='image' and @src]" >
         *     <xsl:copy>
         *       <xsl:copy-of select="@*[namespace-uri() = '']"/>
         *         <xsl:attribute name="src">
         *           <xsl:value-of select="context:rewriteActionURL(@src)"/>
         *         </xsl:attribute>
         *         <xsl:apply-templates/>
         *       </xsl:copy>
         *   </xsl:template>
         * </pre>
         * 
         * If match is satisfied then modified event is sent to destination contentHandler.
         * 
         * @return null if @type='image' test is not satisfied and 
         *         handleEltWithResource( "input", "src", ... ) otherwise.
         * @throws SAXException if destination contentHandler throws SAXException
         * @see XHTMLRewrite
         * @author d
         */
        private State handleInput
        ( final String ns, final String lnam, final String qnam, final Attributes atts ) 
        throws SAXException {
            final State ret;
            final String typ = atts.getValue( "", "type" );
            if ( "image".equals( typ ) ) {
                ret = handleEltWithResource( "input", SRC_ATT, ns, lnam, qnam, atts );
            } else {
                ret = null;
            }
            return ret;
        }
        /**
         * <!-- handleForm -->
         * Handler for {http://www.w3.org/1999/xhtml}input.  Assumes namespace test has already 
         * happened.  Implements : 
         * <pre>
         *   <xsl:template match="form | xhtml:form">
         *     <xsl:copy>
         *       <xsl:copy-of select="@*[namespace-uri() = '']"/>
         *       <xsl:choose>
         *         <xsl:when test="@action">
         *           <xsl:attribute name="action">
         *             <xsl:value-of select="context:rewriteActionURL(@action)"/>
         *           </xsl:attribute>
         *         </xsl:when>
         *         <xsl:otherwise>
         *           <xsl:attribute name="action">
         *             <xsl:value-of select="context:rewriteActionURL('')"/>
         *           </xsl:attribute>
         *         </xsl:otherwise>
         *       </xsl:choose>
         *       <!-- Default is POST instead of GET for portlets -->
         *       <xsl:if test="not(@method) and $container-type/* = 'portlet'">
         *         <xsl:attribute name="method">post</xsl:attribute>
         *       </xsl:if>
         *       <xsl:choose>
         *         <xsl:when test="@portlet:is-portlet-form = 'true'">
         *           <xsl:apply-templates mode="norewrite"/>
         *         </xsl:when>
         *         <xsl:otherwise>
         *           <xsl:apply-templates/>
         *         </xsl:otherwise>
         *       </xsl:choose>
         *     </xsl:copy>
         *   </xsl:template>
         * </pre>
         * 
         * If match is satisfied then modified event is sent to destination contentHandler.

         * @return null match is not satisfied, 
         *         new NoRewriteState( this, contentHandler, response, isPortlet
         *                             , haveScriptAncestor ) if is-portlet-form='true', and this
         *         otherwise.
         * @throws SAXException if destination contentHandler throws SAXException
         * @see XHTMLRewrite
         * @author d
         */
        private State handleForm
        ( final String ns, final String lnam, final String qnam, final Attributes atts ) 
        throws SAXException {
            final State ret;
            if ( "form".equals( lnam ) ) {

                final AttributesImpl newAtts = XMLUtils.getAttribsFromDefaultNamespace( atts );

                final String actn = atts.getValue( "", ACTION_ATT );
                final String newActn;
                if ( actn == null ) {
                    newActn = response.rewriteActionURL( "" );
                } else {
                    newActn = response.rewriteActionURL( actn );
                }
                newAtts.addAttribute( "", ACTION_ATT, ACTION_ATT, "", newActn );
                
                if ( atts.getValue( "", METHOD_ATT ) == null && isPortlet ) {
                    newAtts.addAttribute( "", METHOD_ATT, METHOD_ATT, "", "post" );
                }
                
                final String isPrtltFrm 
                    = atts.getValue( "http://orbeon.org/oxf/xml/portlet", "is-portlet-form" );
                if ( "true".equals( isPrtltFrm ) ) {
                    ret = new NoRewriteState
                        ( this, contentHandler, response, isPortlet, haveScriptAncestor );
                } else {
                    ret = this;
                }
                contentHandler.startElement( ns, lnam, qnam, newAtts  );
            } else {
                ret = null;
            }
            return ret;
        }
        /**
         * <!-- flushCharacters -->
         * If we have accumlated character data rewrite it and forward it.  Implements :
         * <pre>
         *   <xsl:template match="text()">
         *     <xsl:value-of 
         *       select="replace(current(), 'wsrp_rewrite', 'wsrp_rewritewsrp_rewrite')"/>
         *     <xsl:apply-templates/>
         *   </xsl:template>
         * </pre>
         * If there no character data has been accumulated do nothing.  Also clears buffer.
         * @see XHTMLRewrite
         * @see RewriteState
         * @author d
         */
        private void flushCharacters() throws SAXException {
            final int bfLen = charactersBuf == null ? 0 : charactersBuf.position();
            if ( bfLen > 0 ) {
                charactersBuf.flip();
                char[] chs = charactersBuf.array();
                final int chsStrt = charactersBuf.arrayOffset();
                wsrprewriteMatcher.reset( charactersBuf );
                int last = 0;
                while ( wsrprewriteMatcher.find() ) {
                    final int strt = wsrprewriteMatcher.start();
                    final int len = strt - last;
                    //ensureOutbufCapacity( len );
                    //copyFromInToOut( last, strt );
                    //contentHandler.characters( outBuf, 0, len );
                    contentHandler.characters( chs, chsStrt + strt, len );
                    contentHandler.characters( WSRP_REWRITE_REPLACMENT_CHARS, 0, WSRP_REWRITE_REPLACMENT_CHARS.length );
                    last = wsrprewriteMatcher.end();
                }
                if ( last < bfLen )
                {
                    final int len = bfLen - last;
                    //ensureOutbufCapacity( len );
                    //copyFromInToOut( last, bfLen );
                    //contentHandler.characters( outBuf, 0, len );
                    contentHandler.characters( chs, chsStrt + last, len );
                }
                charactersBuf.clear();
            }
        }
        /**
         * <!-- characters -->
         * If haveScriptAncestor then just forward data to destination contentHandler.  Otherwise
         * store that data in the buffer and do not forward.  Also manages init'ing and growing
         * charactersBuf as need be.
         * @see #charactersBuf
         * @see XHTMLRewrite
         * @see RewriteState
         * @author d
         */
        public State characters( final char[] ch, final int strt, final int len ) 
        throws SAXException {
            if ( haveScriptAncestor ) {
                contentHandler.characters( ch, strt, len );
            } else {
                final int bufLen = charactersBuf == null ? 0 : charactersBuf.position();  
                final int cpcty = bufLen + ( len * 2 );
                if ( charactersBuf == null || charactersBuf.remaining() < cpcty ) {
                    final java.nio.CharBuffer newBuf = java.nio.CharBuffer.allocate( cpcty );
                    if ( charactersBuf != null ) {
                        charactersBuf.flip();
                        newBuf.put( charactersBuf );
                    }
                    charactersBuf = newBuf;
                }
                charactersBuf.put( ch, strt, len );
            }
            return this;
        }
        /**
         * <!-- endElement -->
         * Just calls flushCharacters and super.endElement( ... )
         * @see State#endElement(String, String, String)
         * @author dsmall d
         */
        public State endElement( final String ns, final String lnam, final String qnam ) 
        throws SAXException {
            flushCharacters();
            return super.endElement( ns, lnam, qnam );
        }
        /**
         * <!-- ignorableWhitespace -->
         * Just calls flushCharacters and super.endElement( ... )
         * @see State#ignorableWhitespace(char[], int, int)
         * @author dsmall d
         */
        public State ignorableWhitespace( final char[] ch, final int strt, final int len ) 
        throws SAXException {
            flushCharacters();
            return super.ignorableWhitespace(ch, strt, len);
        }
        /**
         * <!-- processingInstruction -->
         * Just calls flushCharacters and super.endElement( ... )
         * @see State#processingInstruction(String, String)
         * @author dsmall d
         */
        public State processingInstruction( final String trgt, final String dat ) 
        throws SAXException {
            flushCharacters();
            return super.processingInstruction( trgt, dat );
        }
        /**
         * <!-- skippedEntity -->
         * Just calls flushCharacters and super.endElement( ... )
         * @see State#skippedEntity(String)
         * @author dsmall d
         */
        public State skippedEntity( final String nam ) throws SAXException {
            flushCharacters();
            return super.skippedEntity( nam );
        }
        /**
         * <!-- startElement -->
         * Just calls flushCharacters then tests the event data.  If
         * <ul>
         *   <li>
         *     @url-norewrite='true' then forward the event to the destination content handler and
         *     return new NoRewriteState( ... ), otherwise
         *   </li>
         *   <li>
         *     if ns.equals( XHTML_URI ) then
         *     <ul>
         *       <li>if one of the handleXXX methods returns non-null do nothing, otherwise</li>
         *       <li>
         *         forward the event to the destination content handler and return this, otherwise
         *       </li>
         *     </ul>
         *   </li>
         *   <li>
         *     if the element is {http://orbeon.org/oxf/xml/formatting}rewrite then implement :
         *     <pre>
         *       <xsl:choose>
         *         <xsl:when test="@type = 'action'">
         *           <xsl:value-of select="context:rewriteActionURL(@url)"/>
         *         </xsl:when>
         *         <xsl:when test="@type = 'render'">
         *           <xsl:value-of select="context:rewriteRenderURL(@url)"/>
         *         </xsl:when>
         *         <xsl:otherwise>
         *           <xsl:value-of select="context:rewriteResourceURL(@url)"/>
         *         </xsl:otherwise>
         *       </xsl:choose>
         *     </pre>
         *     Note this means that we forward characters to the destination content handler instead
         *     of a start element event, otherwise
         *   </li>
         *   <li>
         *     simply forward the event as is to the destination content handler and return this.
         *   </li>
         * </ul>
         * @see State#skippedEntity(String)
         * @author dsmall d
         */
        public State startElement
        ( final String ns, final String lnam, final String qnam, final Attributes atts ) 
        throws SAXException {
            flushCharacters();
            final String no_urlrewrite = atts.getValue( FORMATTING_URI, "url-norewrite" );
            depth++;            
            State ret = this;
            if ( "true".equals( no_urlrewrite ) ) {
                final AttributesImpl newAtts = XMLUtils.getAttribsFromDefaultNamespace( atts );
                contentHandler.startElement( ns, lnam, qnam, newAtts  );
                ret = new NoRewriteState
                    ( this, contentHandler, response, isPortlet, haveScriptAncestor );
            } else if ( "http://www.w3.org/1999/xhtml".equals( ns ) ) {
                done : {
                    ret = handleA( ns, lnam, qnam, atts );
                    if ( ret != null ) break done;
                    
                    ret = handleForm( ns, lnam, qnam, atts );
                    if ( ret != null ) break done;
                    
                    ret = handleArea( ns, lnam, qnam, atts );
                    if ( ret != null ) break done;
                    
                    ret = handleEltWithResource( "link", HREF_ATT, ns, lnam, qnam, atts );
                    if ( ret != null ) break done;
                    
                    ret = handleEltWithResource( "img", SRC_ATT, ns, lnam, qnam, atts );
                    if ( ret != null ) break done;
                    
                    ret = handleInput( ns, lnam, qnam, atts );
                    if ( ret != null ) break done;
                    
                    ret = handleScript( ns, lnam, qnam, atts );
                    if ( ret != null ) break done;
                    
                    ret = handleEltWithResource( "td", BACKGROUND_ATT, ns, lnam, qnam, atts );
                    if ( ret != null ) break done;
                    
                    ret = handleEltWithResource( "body", BACKGROUND_ATT, ns, lnam, qnam, atts );
                    if ( ret != null ) break done;
                    
                    ret = this;
                    contentHandler.startElement( ns, lnam, qnam, atts  );
                }
            } else if ( FORMATTING_URI.equals( ns ) && "rewrite".equals( lnam ) ) {
                final String typ = atts.getValue( "", "type" );
                final String url = atts.getValue( "", "url" );
                if ( url != null ) {
                    final String newURL;
                    if ( "action".equals( typ ) ) {
                        newURL = response.rewriteActionURL( url );
                    } else if ( "render".equals( typ ) ) {
                        newURL = response.rewriteRenderURL( url );
                    } else {
                        newURL = response.rewriteResourceURL( url, false );
                    }
                    final char[] chs = newURL.toCharArray();
                    contentHandler.characters( chs, 0, chs.length );
                }
            } else {
                ret = this;
                contentHandler.startElement( ns, lnam, qnam, atts  );
            }
            return ret;
        }

    }
    /**
     * <!-- NoRewriteState -->
     * Essentially this corresponds to the norewrite mode of oxf-rewrite.xsl.  i.e.  Just forwards
     * events to the destination content handler until we finish the initial element ( depth < 0 )
     * or until it encounters @url-norewrite='false'.  In the first case transitions to the previous
     * state and in the second case it transitions to 
     * new RewriteState( this, contentHandler, response, isPortlet, haveScriptAncestor ).
     * @author dsmall
     */
    static class NoRewriteState extends State {
        NoRewriteState( final State stt, final ContentHandler cntntHndlr, final Response rspns
                        , final boolean isPrtlt, final boolean scrptAncstr ) {
            super( stt, cntntHndlr, rspns, isPrtlt, scrptAncstr );
        }
        /**
         * <!-- startElement -->
         * @see NoRewriteState
         * @author d
         */
        public State startElement
        ( final String ns, final String lnam, final String qnam, final Attributes atts ) 
        throws SAXException {
            final String no_urlrewrite = atts .getValue( FORMATTING_URI, "url-norewrite" );
            final Attributes newAtts = XMLUtils.getAttribsFromDefaultNamespace( atts );
            contentHandler.startElement( ns, lnam, qnam, newAtts  );
            depth++;            
            final State ret;
            if ( "false".equals( no_urlrewrite) ) {
                ret = new RewriteState
                    ( this, contentHandler, response, isPortlet, haveScriptAncestor );
            } else {
                ret = this; 
            }
            return ret;
        }
    }
    /**
     * <!-- StatefullHandler -->
     * Driver for our state machine.  Just forwards SAX events to a State which in turn returns the
     * next State.  The initial state is RootFilter
     * @see XHTMLRewrite
     * @see State 
     * @see RootFilter
     * @author d
     */
    static final class StatefullHandler implements ContentHandler {
        /**
         * <!-- state -->
         * The current state.
         * @see State
         * @see StatefullHandler
         * @author d
         */
        private State state;
        /**
         * <!-- StatefullHandler -->
         * @see StatefullHandler
         * @author d
         */
        StatefullHandler( final ContentHandler dst, final Response rspns, final boolean isPrtlt ) {
            state = new RootFilter( null, dst, rspns, isPrtlt, false );    
        }
        /**
         * <!-- characters -->
         * @see StatefullHandler
         * @author d
         */
        public void characters( final char[] ch, final int strt, final int len ) 
        throws SAXException {
            state = state.characters( ch, strt, len );
        }
        /**
         * <!-- endDocument -->
         * @see StatefullHandler
         * @author d
         */
        public void endDocument() throws SAXException {
            state = state.endDocument();
        }
        /**
         * <!-- endElement -->
         * @see StatefullHandler
         * @author d
         */
        public void endElement( final String ns, final String lnam, final String qnam ) 
        throws SAXException {
            state = state.endElement( ns, lnam, qnam );
        }
        /**
         * <!-- endPrefixMapping -->
         * @see StatefullHandler
         * @author d
         */
        public void endPrefixMapping( final String pfx ) throws SAXException {
            state = state.endPrefixMapping(pfx);
        }
        /**
         * <!-- ignorableWhitespace -->
         * @see StatefullHandler
         * @author d
         */
        public void ignorableWhitespace( final char[] ch, final int strt, final int len ) 
        throws SAXException {
            state = state.ignorableWhitespace( ch, strt, len );
        }
        /**
         * <!-- processingInstruction -->
         * @see StatefullHandler
         * @author d
         */
        public void processingInstruction( final String trgt, final String dat ) 
        throws SAXException {
            state = state.processingInstruction( trgt, dat );
        }
        /**
         * <!-- setDocumentLocator -->
         * @see StatefullHandler
         * @author d
         */
        public void setDocumentLocator( final Locator loc ) {
            state = state.setDocumentLocator( loc );
        }
        /**
         * <!-- skippedEntity -->
         * @see StatefullHandler
         * @author d
         */
        public void skippedEntity( final String nam ) throws SAXException {
            state = state.skippedEntity( nam );
        }
        /**
         * <!-- startDocument -->
         * @see StatefullHandler
         * @author d
         */
        public void startDocument() throws SAXException {
            state = state.startDocument();
        }
        /**
         * <!-- startElement -->
         * @see StatefullHandler
         * @author d
         */
        public void startElement
        ( final String ns, final String lnam, final String qnam, final Attributes atts ) 
        throws SAXException {
            state = state.startElement( ns, lnam, qnam, atts );
        }
        /**
         * <!-- startPrefixMapping -->
         * @see StatefullHandler
         * @author d
         */
        public void startPrefixMapping( final String pfx, final String uri ) throws SAXException {
            state = state.startPrefixMapping( pfx, uri );
        }
    }
    /**
     * <!-- RewriteOutput -->
     * @see #readImpl(PipelineContext, ContentHandler)
     * @author dsmall
     */
    private final class RewriteOutput extends ProcessorImpl.CacheableTransformerOutputImpl {
        /**
         * <!-- RewriteOutput -->
         * Just calls super( ... )
         * @author d
         */
        private RewriteOutput( final Class cls, final String nam ) {
            super( cls, nam );
        }
        /**
         * <!-- readImpl -->
         * Creates a StatefullHandler and uses that to translate the events from the input,
         * rewrite-in, and then send them to the contentHandler ( the output ).
         * @see XHTMLRewrite
         * @see StatefullHandler 
         * @author d
         */
        public void readImpl( final PipelineContext ctxt, final ContentHandler cntntHndlr ) {
            final ExternalContext extrnlCtxt 
                = ( ExternalContext )ctxt.getAttribute( PipelineContext.EXTERNAL_CONTEXT );

            final Response rspns = extrnlCtxt.getResponse();
            
            // Do the conversion
            final boolean isPrtlt = extrnlCtxt instanceof PortletExternalContext;

            final StatefullHandler stFlHndlr= new StatefullHandler( cntntHndlr, rspns, isPrtlt );
            
            readInputAsSAX( ctxt, REWRITE_IN, stFlHndlr );
        }
    }
    /**
     * <!-- XHTMLRewrite -->
     * Just declares input 'rewrite-in' and output 'rewrite-out'.
     * @author d
     */
    public XHTMLRewrite() {
        final ProcessorInputOutputInfo in = new ProcessorInputOutputInfo( REWRITE_IN ); 
        addInputInfo( in );
        final ProcessorInputOutputInfo out = new ProcessorInputOutputInfo( "rewrite-out" ); 
        addOutputInfo( out );
    }
    /**
     * <!-- createOutput -->
     * @author d
     */
    public ProcessorOutput createOutput( final String nam ) {
        final Class cls = getClass();
        final ProcessorOutput ret = new RewriteOutput( cls, nam );
        addOutput( nam, ret );
        return ret;
    }

}
