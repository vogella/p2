/*******************************************************************************
 * Copyright (c) 2009 Cloudsmith Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Cloudsmith Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.p2.metadata.expression;

import java.util.Iterator;
import org.eclipse.equinox.p2.metadata.expression.IEvaluationContext;
import org.eclipse.equinox.p2.metadata.expression.IExpressionVisitor;

/**
 * Some kind of operation that is performed for each element of a collection. I.e.
 * <code>x.&lt;operation&gt;(y | &lt;expression&rt;)</code>
 */
public abstract class CollectionFilter extends Unary {
	public static void appendProlog(StringBuffer bld, Variable rootVariable, Expression lhs, String operator) {
		if (lhs != rootVariable) {
			appendOperand(bld, rootVariable, lhs, PRIORITY_COLLECTION);
			bld.append('.');
		}
		bld.append(operator);
		bld.append('(');
	}

	public final LambdaExpression lambda;

	protected CollectionFilter(Expression collection, LambdaExpression lambda) {
		super(collection);
		this.lambda = lambda;
	}

	public boolean accept(IExpressionVisitor visitor) {
		return super.accept(visitor) && lambda.accept(visitor);
	}

	public int compareTo(Expression e) {
		int cmp = super.compareTo(e);
		if (cmp == 0)
			cmp = lambda.compareTo(((CollectionFilter) e).lambda);
		return cmp;
	}

	public boolean equals(Object o) {
		return super.equals(o) && lambda.equals(((CollectionFilter) o).lambda);
	}

	public final Object evaluate(IEvaluationContext context) {
		Iterator<?> lval = operand.evaluateAsIterator(context);
		context = lambda.prolog(context);
		return evaluate(context, lval);
	}

	public final Iterator<?> evaluateAsIterator(IEvaluationContext context) {
		Iterator<?> lval = operand.evaluateAsIterator(context);
		context = lambda.prolog(context);
		return evaluateAsIterator(context, lval);
	}

	public void toString(StringBuffer bld, Variable rootVariable) {
		appendProlog(bld, rootVariable, operand, getOperator());
		appendOperand(bld, rootVariable, lambda, PRIORITY_LAMBDA);
		bld.append(')');
	}

	public int hashCode() {
		int result = 31 + operand.hashCode();
		return 31 * result + lambda.hashCode();
	}

	public int getPriority() {
		return PRIORITY_COLLECTION;
	}

	protected abstract Object evaluate(final IEvaluationContext context, Iterator<?> iterator);

	protected Iterator<?> evaluateAsIterator(IEvaluationContext context, Iterator<?> iterator) {
		throw new UnsupportedOperationException();
	}
}