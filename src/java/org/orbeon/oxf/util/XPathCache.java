/**
 *  Copyright (C) 2004-2007 Orbeon, Inc.
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
package org.orbeon.oxf.util;

import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.PoolableObjectFactory;
import org.apache.log4j.Logger;
import org.orbeon.oxf.cache.Cache;
import org.orbeon.oxf.cache.InternalCacheKey;
import org.orbeon.oxf.cache.ObjectCache;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.xml.XPathCacheStandaloneContext;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData;
import org.orbeon.saxon.expr.ExpressionTool;
import org.orbeon.saxon.expr.Expression;
import org.orbeon.saxon.expr.ComputedExpression;
import org.orbeon.saxon.expr.Container;
import org.orbeon.saxon.functions.FunctionLibrary;
import org.orbeon.saxon.functions.FunctionLibraryList;
import org.orbeon.saxon.om.NodeInfo;
import org.orbeon.saxon.om.FastStringBuffer;
import org.orbeon.saxon.sxpath.XPathEvaluator;
import org.orbeon.saxon.sxpath.XPathExpression;
import org.orbeon.saxon.trans.IndependentContext;
import org.orbeon.saxon.trans.Variable;
import org.orbeon.saxon.trans.XPathException;
import org.orbeon.saxon.instruct.Executable;
import org.orbeon.saxon.instruct.LocationMap;
import org.orbeon.saxon.event.LocationProvider;
import org.orbeon.saxon.Configuration;
import org.orbeon.saxon.type.Type;
import org.orbeon.saxon.style.AttributeValueTemplate;

import java.util.*;

/**
 * Use the object cache to cache XPath expressions, which are costly to parse.
 *
 * It is mandatory to call returnToPool() on the returned PooledXPathExpression after use. It is
 * good to do this within a finally() block enclosing the use of the expression.
 */
public class XPathCache {

    private static final String XPATH_CACHE_NAME = "cache.xpath";
    private static final int XPATH_CACHE_DEFAULT_SIZE = 200;

    private static final Logger logger = LoggerFactory.createLogger(XPathCache.class);

    /**
     * Evaluate an XPath expression on the document.
     */
    public static List evaluate(PipelineContext pipelineContext, NodeInfo contextNode, String xpathString,
                         Map prefixToURIMap, Map variableToValueMap, FunctionLibrary functionLibrary, Object functionContext, String baseURI, LocationData locationData) {
        return evaluate(pipelineContext, Collections.singletonList(contextNode), 1, xpathString, prefixToURIMap, variableToValueMap, functionLibrary, functionContext, baseURI, locationData);
    }

    /**
     * Evaluate an XPath expression on the document.
     */
    public static List evaluate(PipelineContext pipelineContext, List contextNodeSet, int contextPosition, String xpathString,
                         Map prefixToURIMap, Map variableToValueMap, FunctionLibrary functionLibrary, Object functionContext, String baseURI, LocationData locationData) {

        final PooledXPathExpression xpathExpression = XPathCache.getXPathExpression(pipelineContext, contextNodeSet, contextPosition,
                xpathString, prefixToURIMap, variableToValueMap, functionLibrary, baseURI, false, false, locationData);
        try {
            return xpathExpression.evaluateKeepNodeInfo(functionContext);
        } catch (Exception e) {
            throw handleXPathException(e, xpathString, "evaluating XPath expression", locationData);
        } finally {
            if (xpathExpression != null)
                xpathExpression.returnToPool();
        }
    }

    /**
     * Evaluate an XPath expression on the document.
     */
    public static Object evaluateSingle(PipelineContext pipelineContext, NodeInfo contextNode, String xpathString,
                                 Map prefixToURIMap, Map variableToValueMap, FunctionLibrary functionLibrary, Object functionContext, String baseURI, LocationData locationData) {
        return evaluateSingle(pipelineContext, Collections.singletonList(contextNode), 1, xpathString, prefixToURIMap, variableToValueMap, functionLibrary, functionContext, baseURI, locationData);
    }

    /**
     * Evaluate an XPath expression on the document.
     */
    public static Object evaluateSingle(PipelineContext pipelineContext, List contextNodeSet, int contextPosition, String xpathString,
                                 Map prefixToURIMap, Map variableToValueMap, FunctionLibrary functionLibrary, Object functionContext, String baseURI, LocationData locationData) {

        final PooledXPathExpression xpathExpression = XPathCache.getXPathExpression(pipelineContext, contextNodeSet, contextPosition,
                xpathString, prefixToURIMap, variableToValueMap, functionLibrary, baseURI, false, false, locationData);
        try {
            return xpathExpression.evaluateSingleKeepNodeInfo(functionContext);
        } catch (XPathException e) {
            throw handleXPathException(e, xpathString, "evaluating XPath expression", locationData);
        } finally {
            if (xpathExpression != null)
                xpathExpression.returnToPool();
        }
    }

    /**
     * Evaluate an XPath expression on the document as an attribute value template, and return its string value.
     */
    public static String evaluateAsAvt(PipelineContext pipelineContext, NodeInfo contextNode, String xpathString, Map prefixToURIMap, Map variableToValueMap, FunctionLibrary functionLibrary, Object functionContext, String baseURI, LocationData locationData) {
        return evaluateAsAvt(pipelineContext, Collections.singletonList(contextNode), 1, xpathString, prefixToURIMap, variableToValueMap, functionLibrary, functionContext, baseURI, locationData);
    }

    /**
     * Evaluate an XPath expression on the document as an attribute value template, and return its string value.
     */
    public static String evaluateAsAvt(PipelineContext pipelineContext, List contextNodeSet, int contextPosition, String xpathString, Map prefixToURIMap, Map variableToValueMap, FunctionLibrary functionLibrary, Object functionContext, String baseURI, LocationData locationData) {
        final PooledXPathExpression xpathExpression = XPathCache.getXPathExpression(pipelineContext, contextNodeSet, contextPosition, xpathString,
                prefixToURIMap, variableToValueMap, functionLibrary, baseURI, true, false, locationData);
        try {
            return xpathExpression.evaluateSingleKeepNodeInfo(functionContext).toString();
        } catch (XPathException e) {
            throw handleXPathException(e, xpathString, "evaluating XPath expression", locationData);
        } finally {
            if (xpathExpression != null) xpathExpression.returnToPool();
        }
    }

    /**
     * Evaluate an XPath expression on the document and return its string value.
     */
    public static String evaluateAsString(PipelineContext pipelineContext, NodeInfo contextNode, String xpathString, Map prefixToURIMap, Map variableToValueMap, FunctionLibrary functionLibrary, Object functionContext, String baseURI, LocationData locationData) {
        return evaluateAsString(pipelineContext, Collections.singletonList(contextNode), 1, xpathString, prefixToURIMap, variableToValueMap, functionLibrary, functionContext, baseURI, locationData);
    }

    /**
     * Evaluate an XPath expression on the document and return its string value.
     */
    public static String evaluateAsString(PipelineContext pipelineContext, List contextNodeSet, int contextPosition, String xpathString, Map prefixToURIMap, Map variableToValueMap, FunctionLibrary functionLibrary, Object functionContext, String baseURI, LocationData locationData) {
        final PooledXPathExpression xpathExpression =  XPathCache.getXPathExpression(pipelineContext, contextNodeSet, contextPosition, "string(subsequence(" + xpathString + ", 1, 1))",
                prefixToURIMap, variableToValueMap, functionLibrary, baseURI, false, false, locationData);
        try {
            return xpathExpression.evaluateSingleKeepNodeInfo(functionContext).toString();
        } catch (XPathException e) {
            throw handleXPathException(e, xpathString, "evaluating XPath expression", locationData);
        } finally {
            if (xpathExpression != null) xpathExpression.returnToPool();
        }
    }

    public static PooledXPathExpression getXPathExpression(PipelineContext pipelineContext,
                                                           NodeInfo contextNode,
                                                           String xpathString,
                                                           LocationData locationData) {
        return getXPathExpression(pipelineContext, contextNode, xpathString, null, null, null, null, locationData);
    }

    public static PooledXPathExpression getXPathExpression(PipelineContext pipelineContext,
                                                           NodeInfo contextNode,
                                                           String xpathString,
                                                           Map prefixToURIMap,
                                                           LocationData locationData) {
        return getXPathExpression(pipelineContext, contextNode, xpathString, prefixToURIMap, null, null, null, locationData);
    }

    public static PooledXPathExpression getXPathExpression(PipelineContext pipelineContext,
                                                           NodeInfo contextNode,
                                                           String xpathString,
                                                           Map prefixToURIMap,
                                                           Map variableToValueMap,
                                                           FunctionLibrary functionLibrary,
                                                           String baseURI,
                                                           LocationData locationData) {
        final List contextNodeSet = Collections.singletonList(contextNode);
        return getXPathExpression(pipelineContext, contextNodeSet, 1, xpathString, prefixToURIMap, variableToValueMap, functionLibrary, baseURI, false, false, locationData);
     }

    private static PooledXPathExpression getXPathExpression(PipelineContext pipelineContext,
                                                           List contextNodeSet, int contextPosition,
                                                           String xpathString,
                                                           Map prefixToURIMap,
                                                           Map variableToValueMap,
                                                           FunctionLibrary functionLibrary,
                                                           String baseURI,
                                                           boolean isAvt,
                                                           boolean testNoCache,
                                                           LocationData locationData) {

        try {
            // Find pool from cache
            final Long validity = new Long(0);
            final Cache cache = ObjectCache.instance(XPATH_CACHE_NAME, XPATH_CACHE_DEFAULT_SIZE);
            final FastStringBuffer cacheKeyString = new FastStringBuffer(xpathString);
            {
                if (functionLibrary != null) {// This is ok
                    cacheKeyString.append('|');
                    cacheKeyString.append(Integer.toString(functionLibrary.hashCode()));
                }
            }
            {
                // NOTE: Mike Kay confirms on 2007-07-04 that compilation depends on the namespace context, so we need
                // to use it as part of the cache key.

                // TODO: PERF: It turns out that this takes a lot of time.
                if (prefixToURIMap != null) {
                    final Map sortedMap = (prefixToURIMap instanceof TreeMap) ? prefixToURIMap : new TreeMap(prefixToURIMap);// this should make sure we always get the keys in the same order
                    for (Iterator i = sortedMap.entrySet().iterator(); i.hasNext();) {
                        final Map.Entry currentEntry = (Map.Entry) i.next();
                        cacheKeyString.append('|');
                        cacheKeyString.append((String) currentEntry.getKey());
                        cacheKeyString.append('=');
                        cacheKeyString.append((String) currentEntry.getValue());
                    }
                }
            }
            {
                // Add this to the key as evaluating "name" as XPath or as AVT is very different!
                cacheKeyString.append('|');
                cacheKeyString.append(Boolean.toString(isAvt));
            }

            // TODO: Add baseURI to cache key

            final PooledXPathExpression expr;
            if (testNoCache) {
                // For testing only: don't get expression from cache
                final Object o = new XFormsCachePoolableObjetFactory(null, xpathString, prefixToURIMap, variableToValueMap, functionLibrary, baseURI, isAvt).makeObject();
                expr = (PooledXPathExpression) o;
            } else {
                // Get or create pool
                final InternalCacheKey cacheKey = new InternalCacheKey("XPath Expression2", cacheKeyString.toString());
                ObjectPool pool = (ObjectPool) cache.findValid(pipelineContext, cacheKey, validity);
                if (pool == null) {
                    pool = createXPathPool(xpathString, prefixToURIMap, variableToValueMap, functionLibrary, baseURI, isAvt);
                    cache.add(pipelineContext, cacheKey, validity, pool);
                }

                // Get object from pool
                final Object o = pool.borrowObject();
                expr = (PooledXPathExpression) o;
            }

            // Set context node and position
            expr.setContextNodeSet(contextNodeSet, contextPosition);

            // Set variables
            expr.setVariables(variableToValueMap);

            return expr;
        } catch (Exception e) {
            throw handleXPathException(e, xpathString, "preparing XPath expression", locationData);
        }
    }

    private static ValidationException handleXPathException(Exception e, String xpathString, String description, LocationData locationData) {
        final ValidationException validationException = ValidationException.wrapException(e, new ExtendedLocationData(locationData, description,
                    new String[] { "expression", xpathString } ));

        // Details of ExtendedLocationData passed are discarded by the constructor for ExtendedLocationData above,
        // so we need to explicitly add them.
        if (locationData instanceof ExtendedLocationData)
            validationException.addLocationData(locationData);

        return validationException;
    }

    private static ObjectPool createXPathPool(String xpathString,
                                              Map prefixToURIMap,
                                              Map variableToValueMap,
                                              FunctionLibrary functionLibrary,
                                              String baseURI,
                                              boolean isAvt) {
        try {
            final SoftReferenceObjectPool pool = new SoftReferenceObjectPool();
            pool.setFactory(new XFormsCachePoolableObjetFactory(pool, xpathString,
                    prefixToURIMap, variableToValueMap, functionLibrary, baseURI, isAvt));

            return pool;
        } catch (Exception e) {
            throw new OXFException(e);
        }
    }

    private static class  XFormsCachePoolableObjetFactory implements PoolableObjectFactory {
        private final String xpathString;
        private final Map prefixToURIMap;
        private final Map variableToValueMap;// TODO: should not store values in cache
        // NOTE: storing the FunctionLibrary in cache is ok if it doesn't hold dynamic references (case of global XFormsFunctionLibrary)
        private final FunctionLibrary functionLibrary;
        private final ObjectPool pool;
        private final String baseURI;
        private final boolean isAvt;

        public XFormsCachePoolableObjetFactory(ObjectPool pool,
                                          String xpathString,
                                          Map prefixToURIMap,
                                          Map variableToValueMap,
                                          FunctionLibrary functionLibrary,
                                          String baseURI,
                                          boolean isAvt) {
            this.pool = pool;
            this.xpathString = xpathString;
            this.prefixToURIMap = prefixToURIMap;
            this.variableToValueMap = variableToValueMap;
            this.functionLibrary = functionLibrary;
            this.baseURI = baseURI;
            this.isAvt = isAvt;
        }

        public void activateObject(Object o) throws Exception {
        }

        public void destroyObject(Object o) throws Exception {
            if (o instanceof PooledXPathExpression) {
                PooledXPathExpression xp = (PooledXPathExpression) o;
                xp.destroy();
            } else
                throw new OXFException(o.toString() + " is not a PooledXPathExpression");
        }

        /**
         * Create and compile an XPath expression object.
         */
        public Object makeObject() throws Exception {
            if (logger.isDebugEnabled())
                logger.debug("makeObject(" + xpathString + ")");

            // Create context
            final IndependentContext independentContext = new XPathCacheStandaloneContext();

            // Set the base URI if specified
            if (baseURI != null)
                independentContext.setBaseURI(baseURI);

            // Declare namespaces
            if (prefixToURIMap != null) {
                for (Iterator i = prefixToURIMap.keySet().iterator(); i.hasNext();) {
                    String prefix = (String) i.next();
                    independentContext.declareNamespace(prefix, (String) prefixToURIMap.get(prefix));
                }
            }

            // Declare variables (we don't use the values here, just the names)
            final Map variables = new HashMap();
            if (variableToValueMap != null) {
                for (Iterator i = variableToValueMap.keySet().iterator(); i.hasNext();) {
                    final String name = (String) i.next();
                    final Variable var = independentContext.declareVariable(name);
                    var.setUseStack(true);// "Indicate that values of variables are to be found on the stack, not in the Variable object itself"
                    variables.put(name, var);
                }
            }

            // Add function library
            if (functionLibrary != null) {
                // This is ok
                ((FunctionLibraryList) independentContext.getFunctionLibrary()).libraryList.add(0, functionLibrary);
            }

            // Create and compile the expression
            try {
                final Expression expression;
                if (isAvt) {
                    final Expression tempExpression = AttributeValueTemplate.make(xpathString, -1, independentContext);
                    // Running typeCheck() is mandatory otherwise things break! This is also done when using evaluator.createExpression()
                    expression = tempExpression.typeCheck(independentContext, Type.ITEM_TYPE);
                } else {
                    // Create Saxon XPath Evaluator
                    final XPathEvaluator evaluator = new XPathEvaluator(independentContext.getConfiguration());
                    evaluator.setStaticContext(independentContext);
                    final XPathExpression exp = evaluator.createExpression(xpathString);
                    expression = exp.getInternalExpression();
                    ExpressionTool.allocateSlots(expression, independentContext.getStackFrameMap().getNumberOfVariables(), independentContext.getStackFrameMap());
                }

                {
                    // Provide an Executable with the only purpose of allowing the evaluate() function find the right
                    // FunctionLibrary
                    if (expression instanceof ComputedExpression) {
                        final ComputedExpression computedExpression = (ComputedExpression) expression;
                        computedExpression.setParentExpression(new Container() {

                            public Executable getExecutable() {
                                return new Executable() {
                                    {
                                        setFunctionLibrary(independentContext.getFunctionLibrary());
                                        setLocationMap(new LocationMap());
                                        setConfiguration(independentContext.getConfiguration());
                                    }
                                };
                            }

                            public LocationProvider getLocationProvider() {
                                return computedExpression.getLocationProvider();
                            }

                            public int getHostLanguage() {
                                return Configuration.JAVA_APPLICATION;
                            }

                            public boolean replaceSubExpression(Expression expression, Expression expression1) {
                                return computedExpression.replaceSubExpression(expression, expression1);
                            }

                            public int getColumnNumber() {
                                return computedExpression.getColumnNumber();
                            }

                            public int getLineNumber() {
                                return computedExpression.getLineNumber();
                            }

                            public String getPublicId() {
                                return computedExpression.getPublicId();
                            }

                            public String getSystemId() {
                                return computedExpression.getSystemId();
                            }
                        });
                    }
                }

                return new PooledXPathExpression(expression, pool, independentContext, variables);
            } catch (Throwable t) {
                throw new OXFException(t);
            }
        }

        public void passivateObject(Object o) throws Exception {
        }

        public boolean validateObject(Object o) {
            return true;
        }
    }
}
