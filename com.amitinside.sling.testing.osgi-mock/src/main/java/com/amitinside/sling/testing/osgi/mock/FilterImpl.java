/**
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 */
package com.amitinside.sling.testing.osgi.mock;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import org.eclipse.osgi.framework.debug.Debug;
import org.eclipse.osgi.framework.internal.core.Msg;
import org.eclipse.osgi.framework.util.Headers;
import org.eclipse.osgi.internal.serviceregistry.ServiceReferenceImpl;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

/**
 * RFC 1960-based Filter. Filter objects can be created by calling the
 * constructor with the desired filter string. A Filter object can be called
 * numerous times to determine if the match argument matches the filter string
 * that was used to create the Filter object.
 *
 * <p>
 * The syntax of a filter string is the string representation of LDAP search
 * filters as defined in RFC 1960: <i>A String Representation of LDAP Search
 * Filters</i> (available at http://www.ietf.org/rfc/rfc1960.txt). It should be
 * noted that RFC 2254: <i>A String Representation of LDAP Search Filters</i>
 * (available at http://www.ietf.org/rfc/rfc2254.txt) supersedes RFC 1960 but
 * only adds extensible matching and is not applicable for this API.
 *
 * <p>
 * The string representation of an LDAP search filter is defined by the
 * following grammar. It uses a prefix format.
 *
 * <pre>
 *   &lt;filter&gt; ::= '(' &lt;filtercomp&gt; ')'
 *   &lt;filtercomp&gt; ::= &lt;and&gt; | &lt;or&gt; | &lt;not&gt; | &lt;item&gt;
 *   &lt;and&gt; ::= '&' &lt;filterlist&gt;
 *   &lt;or&gt; ::= '|' &lt;filterlist&gt;
 *   &lt;not&gt; ::= '!' &lt;filter&gt;
 *   &lt;filterlist&gt; ::= &lt;filter&gt; | &lt;filter&gt; &lt;filterlist&gt;
 *   &lt;item&gt; ::= &lt;simple&gt; | &lt;present&gt; | &lt;substring&gt;
 *   &lt;simple&gt; ::= &lt;attr&gt; &lt;filtertype&gt; &lt;value&gt;
 *   &lt;filtertype&gt; ::= &lt;equal&gt; | &lt;approx&gt; | &lt;greater&gt; | &lt;less&gt;
 *   &lt;equal&gt; ::= '='
 *   &lt;approx&gt; ::= '~='
 *   &lt;greater&gt; ::= '&gt;='
 *   &lt;less&gt; ::= '&lt;='
 *   &lt;present&gt; ::= &lt;attr&gt; '=*'
 *   &lt;substring&gt; ::= &lt;attr&gt; '=' &lt;initial&gt; &lt;any&gt; &lt;final&gt;
 *   &lt;initial&gt; ::= NULL | &lt;value&gt;
 *   &lt;any&gt; ::= '*' &lt;starval&gt;
 *   &lt;starval&gt; ::= NULL | &lt;value&gt; '*' &lt;starval&gt;
 *   &lt;final&gt; ::= NULL | &lt;value&gt;
 * </pre>
 *
 * <code>&lt;attr&gt;</code> is a string representing an attribute, or key, in
 * the properties objects of the registered services. Attribute names are not
 * case sensitive; that is cn and CN both refer to the same attribute.
 * <code>&lt;value&gt;</code> is a string representing the value, or part of
 * one, of a key in the properties objects of the registered services. If a
 * <code>&lt;value&gt;</code> must contain one of the characters
 * '<code>*</code>' or '<code>(</code>' or '<code>)</code>', these characters
 * should be escaped by preceding them with the backslash '<code>\</code>'
 * character. Note that although both the <code>&lt;substring&gt;</code> and
 * <code>&lt;present&gt;</code> productions can produce the
 * <code>'attr=*'</code> construct, this construct is used only to denote a
 * presence filter.
 *
 * <p>
 * Examples of LDAP filters are:
 *
 * <pre>
 *   &quot;(cn=Babs Jensen)&quot;
 *   &quot;(!(cn=Tim Howes))&quot;
 *   &quot;(&(&quot; + Constants.OBJECTCLASS + &quot;=Person)(|(sn=Jensen)(cn=Babs J*)))&quot;
 *   &quot;(o=univ*of*mich*)&quot;
 * </pre>
 *
 * <p>
 * The approximate match (<code>~=</code>) is implementation specific but should
 * at least ignore case and white space differences. Optional are codes like
 * soundex or other smart "closeness" comparisons.
 *
 * <p>
 * Comparison of values is not straightforward. Strings are compared differently
 * than numbers and it is possible for a key to have multiple values. Note that
 * that keys in the match argument must always be strings. The comparison is
 * defined by the object type of the key's value. The following rules apply for
 * comparison:
 *
 * <blockquote>
 * <TABLE BORDER=0>
 * <TR>
 * <TD><b>Property Value Type </b></TD>
 * <TD><b>Comparison Type</b></TD>
 * </TR>
 * <TR>
 * <TD>String</TD>
 * <TD>String comparison</TD>
 * </TR>
 * <TR valign=top>
 * <TD>Integer, Long, Float, Double, Byte, Short, BigInteger, BigDecimal</TD>
 * <TD>numerical comparison</TD>
 * </TR>
 * <TR>
 * <TD>Character</TD>
 * <TD>character comparison</TD>
 * </TR>
 * <TR>
 * <TD>Boolean</TD>
 * <TD>equality comparisons only</TD>
 * </TR>
 * <TR>
 * <TD>[] (array)</TD>
 * <TD>recursively applied to values</TD>
 * </TR>
 * <TR>
 * <TD>Vector</TD>
 * <TD>recursively applied to elements</TD>
 * </TR>
 * </TABLE>
 * Note: arrays of primitives are also supported. </blockquote>
 *
 * A filter matches a key that has multiple values if it matches at least one of
 * those values. For example,
 *
 * <pre>
 * Dictionary d = new Hashtable();
 * d.put("cn", new String[] { "a", "b", "c" });
 * </pre>
 *
 * d will match <code>(cn=a)</code> and also <code>(cn=b)</code>
 *
 * <p>
 * A filter component that references a key having an unrecognizable data type
 * will evaluate to <code>false</code> .
 */

public class FilterImpl implements Filter /* since Framework 1.1 */ {
	/* public methods in org.osgi.framework.Filter */

	/**
	 * Parser class for OSGi filter strings. This class parses the complete
	 * filter string and builds a tree of Filter objects rooted at the parent.
	 */
	private static class Parser {
		private final char[] filterChars;
		private final String filterstring;
		private int pos;

		Parser(final String filterstring) {
			this.filterstring = filterstring;
			this.filterChars = filterstring.toCharArray();
			this.pos = 0;
		}

		FilterImpl parse() throws InvalidSyntaxException {
			FilterImpl filter;
			try {
				filter = this.parse_filter();
			} catch (final ArrayIndexOutOfBoundsException e) {
				throw new InvalidSyntaxException(Msg.FILTER_TERMINATED_ABRUBTLY, this.filterstring);
			}

			if (this.pos != this.filterChars.length) {
				throw new InvalidSyntaxException(
						NLS.bind(Msg.FILTER_TRAILING_CHARACTERS, this.filterstring.substring(this.pos)),
						this.filterstring);
			}
			return filter;
		}

		private FilterImpl parse_and() throws InvalidSyntaxException {
			final int lookahead = this.pos;
			this.skipWhiteSpace();

			if (this.filterChars[this.pos] != '(') {
				this.pos = lookahead - 1;
				return this.parse_item();
			}

			final List<FilterImpl> operands = new ArrayList<FilterImpl>(10);

			while (this.filterChars[this.pos] == '(') {
				final FilterImpl child = this.parse_filter();
				operands.add(child);
			}

			return new FilterImpl(FilterImpl.AND, null, operands.toArray(new FilterImpl[operands.size()]));
		}

		private String parse_attr() throws InvalidSyntaxException {
			this.skipWhiteSpace();

			final int begin = this.pos;
			int end = this.pos;

			char c = this.filterChars[this.pos];

			while ((c != '~') && (c != '<') && (c != '>') && (c != '=') && (c != '(') && (c != ')')) {
				this.pos++;

				if (!Character.isWhitespace(c)) {
					end = this.pos;
				}

				c = this.filterChars[this.pos];
			}

			final int length = end - begin;

			if (length == 0) {
				throw new InvalidSyntaxException(
						NLS.bind(Msg.FILTER_MISSING_ATTR, this.filterstring.substring(this.pos)), this.filterstring);
			}

			return new String(this.filterChars, begin, length);
		}

		private FilterImpl parse_filter() throws InvalidSyntaxException {
			FilterImpl filter;
			this.skipWhiteSpace();

			if (this.filterChars[this.pos] != '(') {
				throw new InvalidSyntaxException(
						NLS.bind(Msg.FILTER_MISSING_LEFTPAREN, this.filterstring.substring(this.pos)),
						this.filterstring);
			}

			this.pos++;

			filter = this.parse_filtercomp();

			this.skipWhiteSpace();

			if (this.filterChars[this.pos] != ')') {
				throw new InvalidSyntaxException(
						NLS.bind(Msg.FILTER_MISSING_RIGHTPAREN, this.filterstring.substring(this.pos)),
						this.filterstring);
			}

			this.pos++;

			this.skipWhiteSpace();

			return filter;
		}

		private FilterImpl parse_filtercomp() throws InvalidSyntaxException {
			this.skipWhiteSpace();

			final char c = this.filterChars[this.pos];

			switch (c) {
			case '&': {
				this.pos++;
				return this.parse_and();
			}
			case '|': {
				this.pos++;
				return this.parse_or();
			}
			case '!': {
				this.pos++;
				return this.parse_not();
			}
			}
			return this.parse_item();
		}

		private FilterImpl parse_item() throws InvalidSyntaxException {
			final String attr = this.parse_attr();

			this.skipWhiteSpace();

			switch (this.filterChars[this.pos]) {
			case '~': {
				if (this.filterChars[this.pos + 1] == '=') {
					this.pos += 2;
					return new FilterImpl(FilterImpl.APPROX, attr, this.parse_value());
				}
				break;
			}
			case '>': {
				if (this.filterChars[this.pos + 1] == '=') {
					this.pos += 2;
					return new FilterImpl(FilterImpl.GREATER, attr, this.parse_value());
				}
				break;
			}
			case '<': {
				if (this.filterChars[this.pos + 1] == '=') {
					this.pos += 2;
					return new FilterImpl(FilterImpl.LESS, attr, this.parse_value());
				}
				break;
			}
			case '=': {
				if (this.filterChars[this.pos + 1] == '*') {
					final int oldpos = this.pos;
					this.pos += 2;
					this.skipWhiteSpace();
					if (this.filterChars[this.pos] == ')') {
						return new FilterImpl(FilterImpl.PRESENT, attr, null);
					}
					this.pos = oldpos;
				}

				this.pos++;
				final Object string = this.parse_substring();

				if (string instanceof String) {
					return new FilterImpl(FilterImpl.EQUAL, attr, string);
				}
				return new FilterImpl(FilterImpl.SUBSTRING, attr, string);
			}
			}

			throw new InvalidSyntaxException(
					NLS.bind(Msg.FILTER_INVALID_OPERATOR, this.filterstring.substring(this.pos)), this.filterstring);
		}

		private FilterImpl parse_not() throws InvalidSyntaxException {
			final int lookahead = this.pos;
			this.skipWhiteSpace();

			if (this.filterChars[this.pos] != '(') {
				this.pos = lookahead - 1;
				return this.parse_item();
			}

			final FilterImpl child = this.parse_filter();

			return new FilterImpl(FilterImpl.NOT, null, child);
		}

		private FilterImpl parse_or() throws InvalidSyntaxException {
			final int lookahead = this.pos;
			this.skipWhiteSpace();

			if (this.filterChars[this.pos] != '(') {
				this.pos = lookahead - 1;
				return this.parse_item();
			}

			final List<FilterImpl> operands = new ArrayList<FilterImpl>(10);

			while (this.filterChars[this.pos] == '(') {
				final FilterImpl child = this.parse_filter();
				operands.add(child);
			}

			return new FilterImpl(FilterImpl.OR, null, operands.toArray(new FilterImpl[operands.size()]));
		}

		private Object parse_substring() throws InvalidSyntaxException {
			final StringBuffer sb = new StringBuffer(this.filterChars.length - this.pos);

			final List<String> operands = new ArrayList<String>(10);

			parseloop: while (true) {
				char c = this.filterChars[this.pos];

				switch (c) {
				case ')': {
					if (sb.length() > 0) {
						operands.add(sb.toString());
					}

					break parseloop;
				}

				case '(': {
					throw new InvalidSyntaxException(
							NLS.bind(Msg.FILTER_INVALID_VALUE, this.filterstring.substring(this.pos)),
							this.filterstring);
				}

				case '*': {
					if (sb.length() > 0) {
						operands.add(sb.toString());
					}

					sb.setLength(0);

					operands.add(null);
					this.pos++;

					break;
				}

				case '\\': {
					this.pos++;
					c = this.filterChars[this.pos];
					/* fall through into default */
				}

				default: {
					sb.append(c);
					this.pos++;
					break;
				}
				}
			}

			final int size = operands.size();

			if (size == 0) {
				return ""; //$NON-NLS-1$
			}

			if (size == 1) {
				final Object single = operands.get(0);

				if (single != null) {
					return single;
				}
			}

			return operands.toArray(new String[size]);
		}

		private String parse_value() throws InvalidSyntaxException {
			final StringBuffer sb = new StringBuffer(this.filterChars.length - this.pos);

			parseloop: while (true) {
				char c = this.filterChars[this.pos];

				switch (c) {
				case ')': {
					break parseloop;
				}

				case '(': {
					throw new InvalidSyntaxException(
							NLS.bind(Msg.FILTER_INVALID_VALUE, this.filterstring.substring(this.pos)),
							this.filterstring);
				}

				case '\\': {
					this.pos++;
					c = this.filterChars[this.pos];
					/* fall through into default */
				}

				default: {
					sb.append(c);
					this.pos++;
					break;
				}
				}
			}

			if (sb.length() == 0) {
				throw new InvalidSyntaxException(
						NLS.bind(Msg.FILTER_MISSING_VALUE, this.filterstring.substring(this.pos)), this.filterstring);
			}

			return sb.toString();
		}

		private void skipWhiteSpace() {
			for (final int length = this.filterChars.length; (this.pos < length)
					&& Character.isWhitespace(this.filterChars[this.pos]);) {
				this.pos++;
			}
		}
	}

	/**
	 * This Dictionary is used for key lookup from a ServiceReference during
	 * filter evaluation. This Dictionary implementation only supports the get
	 * operation using a String key as no other operations are used by the
	 * Filter implementation.
	 *
	 */
	private static class ServiceReferenceDictionary extends Dictionary<String, Object> {
		private final ServiceReference<?> reference;

		ServiceReferenceDictionary(final ServiceReference<?> reference) {
			this.reference = reference;
		}

		@Override
		public Enumeration<Object> elements() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Object get(final Object key) {
			if (this.reference == null) {
				return null;
			}
			return this.reference.getProperty((String) key);
		}

		@Override
		public boolean isEmpty() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Enumeration<String> keys() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Object put(final String key, final Object value) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Object remove(final Object key) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int size() {
			throw new UnsupportedOperationException();
		}
	}

	private static class SetAccessibleAction implements PrivilegedAction<Object> {
		private final AccessibleObject accessible;

		SetAccessibleAction(final AccessibleObject accessible) {
			this.accessible = accessible;
		}

		@Override
		public Object run() {
			this.accessible.setAccessible(true);
			return null;
		}
	}

	private static final int AND = 7;

	private static final int APPROX = 2;

	private static final int EQUAL = 1;

	private static final int GREATER = 3;

	private static final int LESS = 4;

	private static final int NOT = 9;

	/* non public fields and methods for the Filter implementation */

	private static final int OR = 8;
	private static final int PRESENT = 5;
	private static final int SUBSTRING = 6;

	/**
	 * Map a string for an APPROX (~=) comparison.
	 *
	 * This implementation removes white spaces. This is the minimum
	 * implementation allowed by the OSGi spec.
	 *
	 * @param input
	 *            Input string.
	 * @return String ready for APPROX comparison.
	 */
	private static String approxString(final String input) {
		boolean changed = false;
		final char[] output = input.toCharArray();
		int cursor = 0;
		for (final char c : output) {
			if (Character.isWhitespace(c)) {
				changed = true;
				continue;
			}

			output[cursor] = c;
			cursor++;
		}

		return changed ? new String(output, 0, cursor) : input;
	}

	/**
	 * Encode the value string such that '(', '*', ')' and '\' are escaped.
	 *
	 * @param value
	 *            unencoded value string.
	 * @return encoded value string.
	 */
	private static String encodeValue(final String value) {
		boolean encoded = false;
		final int inlen = value.length();
		final int outlen = inlen << 1; /* inlen * 2 */

		final char[] output = new char[outlen];
		value.getChars(0, inlen, output, inlen);

		int cursor = 0;
		for (int i = inlen; i < outlen; i++) {
			final char c = output[i];

			switch (c) {
			case '(':
			case '*':
			case ')':
			case '\\': {
				output[cursor] = '\\';
				cursor++;
				encoded = true;

				break;
			}
			}

			output[cursor] = c;
			cursor++;
		}

		return encoded ? new String(output, 0, cursor) : value;
	}

	/**
	 * Constructs a {@link FilterImpl} object. This filter object may be used to
	 * match a {@link ServiceReferenceImpl} or a Dictionary.
	 *
	 * <p>
	 * If the filter cannot be parsed, an {@link InvalidSyntaxException} will be
	 * thrown with a human readable message where the filter became unparsable.
	 *
	 * @param filterString
	 *            the filter string.
	 * @exception InvalidSyntaxException
	 *                If the filter parameter contains an invalid filter string
	 *                that cannot be parsed.
	 */
	public static FilterImpl newInstance(final String filterString) throws InvalidSyntaxException {
		return new Parser(filterString).parse();
	}

	private static void setAccessible(final AccessibleObject accessible) {
		if (!accessible.isAccessible()) {
			AccessController.doPrivileged(new SetAccessibleAction(accessible));
		}
	}

	private static Object valueOf(final Class<?> target, final String value2) {
		do {
			Method method;
			try {
				method = target.getMethod("valueOf", String.class); //$NON-NLS-1$
			} catch (final NoSuchMethodException e) {
				break;
			}
			if (Modifier.isStatic(method.getModifiers()) && target.isAssignableFrom(method.getReturnType())) {
				setAccessible(method);
				try {
					return method.invoke(null, value2.trim());
				} catch (final IllegalAccessException e) {
					return null;
				} catch (final InvocationTargetException e) {
					return null;
				}
			}
		} while (false);

		do {
			Constructor<?> constructor;
			try {
				constructor = target.getConstructor(String.class);
			} catch (final NoSuchMethodException e) {
				break;
			}
			setAccessible(constructor);
			try {
				return constructor.newInstance(value2.trim());
			} catch (final IllegalAccessException e) {
				return null;
			} catch (final InvocationTargetException e) {
				return null;
			} catch (final InstantiationException e) {
				return null;
			}
		} while (false);

		return null;
	}

	/** filter attribute or null if operation AND, OR or NOT */
	private final String attr;
	/* normalized filter string for topLevel Filter object */
	private transient volatile String filterString;

	/** filter operation */
	private final int op;
	/** filter operands */
	private final Object value;

	FilterImpl(final int operation, final String attr, final Object value) {
		this.op = operation;
		this.attr = attr;
		this.value = value;
	}

	private boolean compare(final int operation, final Object value1, final Object value2) {
		if (value1 == null) {
			if (Debug.DEBUG_FILTER) {
				Debug.println("compare(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}

			return false;
		}

		if (value1 instanceof String) {
			return this.compare_String(operation, (String) value1, value2);
		}

		final Class<?> clazz = value1.getClass();
		if (clazz.isArray()) {
			final Class<?> type = clazz.getComponentType();
			if (type.isPrimitive()) {
				return this.compare_PrimitiveArray(operation, type, value1, value2);
			}
			return this.compare_ObjectArray(operation, (Object[]) value1, value2);
		}
		if (value1 instanceof Collection<?>) {
			return this.compare_Collection(operation, (Collection<?>) value1, value2);
		}

		if (value1 instanceof Integer) {
			return this.compare_Integer(operation, ((Integer) value1).intValue(), value2);
		}

		if (value1 instanceof Long) {
			return this.compare_Long(operation, ((Long) value1).longValue(), value2);
		}

		if (value1 instanceof Byte) {
			return this.compare_Byte(operation, ((Byte) value1).byteValue(), value2);
		}

		if (value1 instanceof Short) {
			return this.compare_Short(operation, ((Short) value1).shortValue(), value2);
		}

		if (value1 instanceof Character) {
			return this.compare_Character(operation, ((Character) value1).charValue(), value2);
		}

		if (value1 instanceof Float) {
			return this.compare_Float(operation, ((Float) value1).floatValue(), value2);
		}

		if (value1 instanceof Double) {
			return this.compare_Double(operation, ((Double) value1).doubleValue(), value2);
		}

		if (value1 instanceof Boolean) {
			return this.compare_Boolean(operation, ((Boolean) value1).booleanValue(), value2);
		}
		if (value1 instanceof Comparable<?>) {
			@SuppressWarnings("unchecked")
			final Comparable<Object> comparable = (Comparable<Object>) value1;
			return this.compare_Comparable(operation, comparable, value2);
		}

		return this.compare_Unknown(operation, value1, value2); // RFC 59
	}

	private boolean compare_Boolean(final int operation, final boolean boolval, final Object value2) {
		if (operation == SUBSTRING) {
			if (Debug.DEBUG_FILTER) {
				Debug.println("SUBSTRING(" + boolval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return false;
		}

		final boolean boolval2 = Boolean.valueOf(((String) value2).trim()).booleanValue();
		switch (operation) {
		case EQUAL: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("EQUAL(" + boolval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return boolval == boolval2;
		}
		case APPROX: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("APPROX(" + boolval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return boolval == boolval2;
		}
		case GREATER: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("GREATER(" + boolval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return boolval == boolval2;
		}
		case LESS: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("LESS(" + boolval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return boolval == boolval2;
		}
		}

		return false;
	}

	private boolean compare_Byte(final int operation, final byte byteval, final Object value2) {
		if (operation == SUBSTRING) {
			if (Debug.DEBUG_FILTER) {
				Debug.println("SUBSTRING(" + byteval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return false;
		}

		byte byteval2;
		try {
			byteval2 = Byte.parseByte(((String) value2).trim());
		} catch (final IllegalArgumentException e) {
			return false;
		}
		switch (operation) {
		case EQUAL: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("EQUAL(" + byteval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return byteval == byteval2;
		}
		case APPROX: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("APPROX(" + byteval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return byteval == byteval2;
		}
		case GREATER: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("GREATER(" + byteval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return byteval >= byteval2;
		}
		case LESS: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("LESS(" + byteval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return byteval <= byteval2;
		}
		}

		return false;
	}

	private boolean compare_Character(final int operation, final char charval, final Object value2) {
		if (operation == SUBSTRING) {
			if (Debug.DEBUG_FILTER) {
				Debug.println("SUBSTRING(" + charval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return false;
		}

		char charval2;
		try {
			charval2 = ((String) value2).charAt(0);
		} catch (final IndexOutOfBoundsException e) {
			return false;
		}
		switch (operation) {
		case EQUAL: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("EQUAL(" + charval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return charval == charval2;
		}
		case APPROX: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("APPROX(" + charval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return (charval == charval2) || (Character.toUpperCase(charval) == Character.toUpperCase(charval2))
					|| (Character.toLowerCase(charval) == Character.toLowerCase(charval2));
		}
		case GREATER: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("GREATER(" + charval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return charval >= charval2;
		}
		case LESS: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("LESS(" + charval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return charval <= charval2;
		}
		}

		return false;
	}

	private boolean compare_Collection(final int operation, final Collection<?> collection, final Object value2) {
		for (final Object value1 : collection) {
			if (this.compare(operation, value1, value2)) {
				return true;
			}
		}

		return false;
	}

	private boolean compare_Comparable(final int operation, final Comparable<Object> value1, Object value2) {
		if (operation == SUBSTRING) {
			if (Debug.DEBUG_FILTER) {
				Debug.println("SUBSTRING(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return false;
		}
		value2 = valueOf(value1.getClass(), (String) value2);
		if (value2 == null) {
			return false;
		}

		try {
			switch (operation) {
			case EQUAL: {
				if (Debug.DEBUG_FILTER) {
					Debug.println("EQUAL(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				return value1.compareTo(value2) == 0;
			}
			case APPROX: {
				if (Debug.DEBUG_FILTER) {
					Debug.println("APPROX(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				return value1.compareTo(value2) == 0;
			}
			case GREATER: {
				if (Debug.DEBUG_FILTER) {
					Debug.println("GREATER(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				return value1.compareTo(value2) >= 0;
			}
			case LESS: {
				if (Debug.DEBUG_FILTER) {
					Debug.println("LESS(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				return value1.compareTo(value2) <= 0;
			}
			}
		} catch (final Exception e) {
			// if the compareTo method throws an exception; return false
			return false;
		}
		return false;
	}

	private boolean compare_Double(final int operation, final double doubleval, final Object value2) {
		if (operation == SUBSTRING) {
			if (Debug.DEBUG_FILTER) {
				Debug.println("SUBSTRING(" + doubleval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return false;
		}

		double doubleval2;
		try {
			doubleval2 = Double.parseDouble(((String) value2).trim());
		} catch (final IllegalArgumentException e) {
			return false;
		}
		switch (operation) {
		case EQUAL: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("EQUAL(" + doubleval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return Double.compare(doubleval, doubleval2) == 0;
		}
		case APPROX: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("APPROX(" + doubleval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return Double.compare(doubleval, doubleval2) == 0;
		}
		case GREATER: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("GREATER(" + doubleval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return Double.compare(doubleval, doubleval2) >= 0;
		}
		case LESS: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("LESS(" + doubleval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return Double.compare(doubleval, doubleval2) <= 0;
		}
		}

		return false;
	}

	private boolean compare_Float(final int operation, final float floatval, final Object value2) {
		if (operation == SUBSTRING) {
			if (Debug.DEBUG_FILTER) {
				Debug.println("SUBSTRING(" + floatval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return false;
		}

		float floatval2;
		try {
			floatval2 = Float.parseFloat(((String) value2).trim());
		} catch (final IllegalArgumentException e) {
			return false;
		}
		switch (operation) {
		case EQUAL: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("EQUAL(" + floatval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return Float.compare(floatval, floatval2) == 0;
		}
		case APPROX: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("APPROX(" + floatval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return Float.compare(floatval, floatval2) == 0;
		}
		case GREATER: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("GREATER(" + floatval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return Float.compare(floatval, floatval2) >= 0;
		}
		case LESS: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("LESS(" + floatval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return Float.compare(floatval, floatval2) <= 0;
		}
		}

		return false;
	}

	private boolean compare_Integer(final int operation, final int intval, final Object value2) {
		if (operation == SUBSTRING) {
			if (Debug.DEBUG_FILTER) {
				Debug.println("SUBSTRING(" + intval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return false;
		}

		int intval2;
		try {
			intval2 = Integer.parseInt(((String) value2).trim());
		} catch (final IllegalArgumentException e) {
			return false;
		}
		switch (operation) {
		case EQUAL: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("EQUAL(" + intval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return intval == intval2;
		}
		case APPROX: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("APPROX(" + intval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return intval == intval2;
		}
		case GREATER: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("GREATER(" + intval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return intval >= intval2;
		}
		case LESS: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("LESS(" + intval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return intval <= intval2;
		}
		}

		return false;
	}

	private boolean compare_Long(final int operation, final long longval, final Object value2) {
		if (operation == SUBSTRING) {
			if (Debug.DEBUG_FILTER) {
				Debug.println("SUBSTRING(" + longval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return false;
		}

		long longval2;
		try {
			longval2 = Long.parseLong(((String) value2).trim());
		} catch (final IllegalArgumentException e) {
			return false;
		}
		switch (operation) {
		case EQUAL: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("EQUAL(" + longval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return longval == longval2;
		}
		case APPROX: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("APPROX(" + longval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return longval == longval2;
		}
		case GREATER: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("GREATER(" + longval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return longval >= longval2;
		}
		case LESS: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("LESS(" + longval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return longval <= longval2;
		}
		}

		return false;
	}

	private boolean compare_ObjectArray(final int operation, final Object[] array, final Object value2) {
		for (final Object value1 : array) {
			if (this.compare(operation, value1, value2)) {
				return true;
			}
		}

		return false;
	}

	private boolean compare_PrimitiveArray(final int operation, final Class<?> type, final Object primarray,
			final Object value2) {
		if (Integer.TYPE.isAssignableFrom(type)) {
			final int[] array = (int[]) primarray;
			for (final int value1 : array) {
				if (this.compare_Integer(operation, value1, value2)) {
					return true;
				}
			}

			return false;
		}

		if (Long.TYPE.isAssignableFrom(type)) {
			final long[] array = (long[]) primarray;
			for (final long value1 : array) {
				if (this.compare_Long(operation, value1, value2)) {
					return true;
				}
			}

			return false;
		}

		if (Byte.TYPE.isAssignableFrom(type)) {
			final byte[] array = (byte[]) primarray;
			for (final byte value1 : array) {
				if (this.compare_Byte(operation, value1, value2)) {
					return true;
				}
			}

			return false;
		}

		if (Short.TYPE.isAssignableFrom(type)) {
			final short[] array = (short[]) primarray;
			for (final short value1 : array) {
				if (this.compare_Short(operation, value1, value2)) {
					return true;
				}
			}

			return false;
		}

		if (Character.TYPE.isAssignableFrom(type)) {
			final char[] array = (char[]) primarray;
			for (final char value1 : array) {
				if (this.compare_Character(operation, value1, value2)) {
					return true;
				}
			}

			return false;
		}

		if (Float.TYPE.isAssignableFrom(type)) {
			final float[] array = (float[]) primarray;
			for (final float value1 : array) {
				if (this.compare_Float(operation, value1, value2)) {
					return true;
				}
			}

			return false;
		}

		if (Double.TYPE.isAssignableFrom(type)) {
			final double[] array = (double[]) primarray;
			for (final double value1 : array) {
				if (this.compare_Double(operation, value1, value2)) {
					return true;
				}
			}

			return false;
		}

		if (Boolean.TYPE.isAssignableFrom(type)) {
			final boolean[] array = (boolean[]) primarray;
			for (final boolean value1 : array) {
				if (this.compare_Boolean(operation, value1, value2)) {
					return true;
				}
			}

			return false;
		}

		return false;
	}

	private boolean compare_Short(final int operation, final short shortval, final Object value2) {
		if (operation == SUBSTRING) {
			if (Debug.DEBUG_FILTER) {
				Debug.println("SUBSTRING(" + shortval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return false;
		}

		short shortval2;
		try {
			shortval2 = Short.parseShort(((String) value2).trim());
		} catch (final IllegalArgumentException e) {
			return false;
		}
		switch (operation) {
		case EQUAL: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("EQUAL(" + shortval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return shortval == shortval2;
		}
		case APPROX: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("APPROX(" + shortval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return shortval == shortval2;
		}
		case GREATER: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("GREATER(" + shortval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return shortval >= shortval2;
		}
		case LESS: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("LESS(" + shortval + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return shortval <= shortval2;
		}
		}

		return false;
	}

	private boolean compare_String(final int operation, String string, final Object value2) {
		switch (operation) {
		case SUBSTRING: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("SUBSTRING(" + string + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}

			final String[] substrings = (String[]) value2;
			int pos = 0;
			for (int i = 0, size = substrings.length; i < size; i++) {
				final String substr = substrings[i];

				if ((i + 1) < size) /* if this is not that last substr */ {
					if (substr == null) /* * */ {
						final String substr2 = substrings[i + 1];

						if (substr2 == null) {
							continue; /* ignore first star */
						}
						/* *xxx */
						if (Debug.DEBUG_FILTER) {
							Debug.println("indexOf(\"" + substr2 + "\"," + pos + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						}
						final int index = string.indexOf(substr2, pos);
						if (index == -1) {
							return false;
						}

						pos = index + substr2.length();
						if ((i + 2) < size) {
							i++;
						}
					} else /* xxx */ {
						final int len = substr.length();

						if (Debug.DEBUG_FILTER) {
							Debug.println("regionMatches(" + pos + ",\"" + substr + "\")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						}
						if (string.regionMatches(pos, substr, 0, len)) {
							pos += len;
						} else {
							return false;
						}
					}
				} else /* last substr */ {
					if (substr == null) /* * */ {
						return true;
					}
					/* xxx */
					if (Debug.DEBUG_FILTER) {
						Debug.println("regionMatches(" + pos + "," + substr + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					}
					return string.endsWith(substr);
				}
			}

			return true;
		}
		case EQUAL: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("EQUAL(" + string + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return string.equals(value2);
		}
		case APPROX: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("APPROX(" + string + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}

			string = approxString(string);
			final String string2 = approxString((String) value2);

			return string.equalsIgnoreCase(string2);
		}
		case GREATER: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("GREATER(" + string + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return string.compareTo((String) value2) >= 0;
		}
		case LESS: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("LESS(" + string + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return string.compareTo((String) value2) <= 0;
		}
		}

		return false;
	}

	private boolean compare_Unknown(final int operation, final Object value1, Object value2) { // RFC
																								// 59
		if (operation == SUBSTRING) {
			if (Debug.DEBUG_FILTER) {
				Debug.println("SUBSTRING(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return false;
		}
		value2 = valueOf(value1.getClass(), (String) value2);
		if (value2 == null) {
			return false;
		}

		try {
			switch (operation) {
			case EQUAL: {
				if (Debug.DEBUG_FILTER) {
					Debug.println("EQUAL(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				return value1.equals(value2);
			}
			case APPROX: {
				if (Debug.DEBUG_FILTER) {
					Debug.println("APPROX(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				return value1.equals(value2);
			}
			case GREATER: {
				if (Debug.DEBUG_FILTER) {
					Debug.println("GREATER(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				return value1.equals(value2);
			}
			case LESS: {
				if (Debug.DEBUG_FILTER) {
					Debug.println("LESS(" + value1 + "," + value2 + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				return value1.equals(value2);
			}
			}
		} catch (final Exception e) {
			// if the equals method throws an exception; return false
			return false;
		}

		return false;
	}

	/**
	 * Compares this <code>Filter</code> object to another object.
	 *
	 * @param obj
	 *            The object to compare against this <code>Filter</code> object.
	 * @return If the other object is a <code>Filter</code> object, then returns
	 *         <code>this.toString().equals(obj.toString()</code>;
	 *         <code>false</code> otherwise.
	 */
	@Override
	public boolean equals(final Object obj) {
		if (obj == this) {
			return true;
		}

		if (!(obj instanceof Filter)) {
			return false;
		}

		return this.toString().equals(obj.toString());
	}

	/**
	 * Returns all the attributes contained within this filter
	 *
	 * @return all the attributes contained within this filter
	 */
	public String[] getAttributes() {
		final List<String> results = new ArrayList<String>();
		this.getAttributesInternal(results);
		return results.toArray(new String[results.size()]);
	}

	private void getAttributesInternal(final List<String> results) {
		if (this.value instanceof FilterImpl[]) {
			final FilterImpl[] children = (FilterImpl[]) this.value;
			for (final FilterImpl child : children) {
				child.getAttributesInternal(results);
			}
			return;
		} else if (this.value instanceof FilterImpl) {
			// The NOT operation only has one child filter (bug 188075)
			final FilterImpl child = ((FilterImpl) this.value);
			child.getAttributesInternal(results);
			return;
		}
		if (this.attr != null) {
			results.add(this.attr);
		}
	}

	/**
	 * Returns the leftmost required primary key value for the filter to
	 * evaluate to true. This is useful for indexing candidates to match against
	 * this filter.
	 *
	 * @param primaryKey
	 *            the primary key
	 * @return The leftmost required primary key value or null if none could be
	 *         determined.
	 */
	public String getPrimaryKeyValue(final String primaryKey) {
		// just checking for simple filters here where primaryKey is the only
		// attr or it is one attr of a base '&' clause
		// (primaryKey=org.acme.BrickService) OK
		// (&(primaryKey=org.acme.BrickService)(|(vendor=IBM)(vendor=SUN))) OK
		// (primaryKey=org.acme.*) NOT OK
		// (|(primaryKey=org.acme.BrickService)(primaryKey=org.acme.CementService))
		// NOT OK
		// (&(primaryKey=org.acme.BrickService)(primaryKey=org.acme.CementService))
		// OK but only the first objectClass is returned
		switch (this.op) {
		case EQUAL:
			if (this.attr.equalsIgnoreCase(primaryKey) && (this.value instanceof String)) {
				return (String) this.value;
			}
			break;
		case AND:
			final FilterImpl[] clauses = (FilterImpl[]) this.value;
			for (final FilterImpl clause : clauses) {
				if (clause.op == EQUAL) {
					final String result = clause.getPrimaryKeyValue(primaryKey);
					if (result != null) {
						return result;
					}
				}
			}
			break;
		}
		return null;
	}

	/**
	 * Returns the leftmost required objectClass value for the filter to
	 * evaluate to true.
	 *
	 * @return The leftmost required objectClass value or null if none could be
	 *         determined.
	 */
	public String getRequiredObjectClass() {
		return this.getPrimaryKeyValue(Constants.OBJECTCLASS);
	}

	/**
	 * Returns the hashCode for this <code>Filter</code> object.
	 *
	 * @return The hashCode of the filter string; that is,
	 *         <code>this.toString().hashCode()</code>.
	 */
	@Override
	public int hashCode() {
		return this.toString().hashCode();
	}

	/**
	 * Filter using a {@code Dictionary} with case insensitive key lookup. This
	 * {@code Filter} is executed using the specified {@code Dictionary}'s keys
	 * and values. The keys are looked up in a case insensitive manner.
	 *
	 * @param dictionary
	 *            The {@code Dictionary} whose key/value pairs are used in the
	 *            match.
	 * @return {@code true} if the {@code Dictionary}'s values match this
	 *         filter; {@code false} otherwise.
	 * @throws IllegalArgumentException
	 *             If {@code dictionary} contains case variants of the same key
	 *             name.
	 */
	@Override
	public boolean match(Dictionary<String, ?> dictionary) {
		if (dictionary != null) {
			dictionary = new Headers<String, Object>(dictionary);
		}

		return this.matchCase(dictionary);
	}

	/**
	 * Filter using a service's properties.
	 * <p>
	 * This {@code Filter} is executed using the keys and values of the
	 * referenced service's properties. The keys are looked up in a case
	 * insensitive manner.
	 *
	 * @param reference
	 *            The reference to the service whose properties are used in the
	 *            match.
	 * @return {@code true} if the service's properties match this
	 *         {@code Filter}; {@code false} otherwise.
	 */
	@Override
	public boolean match(final ServiceReference<?> reference) {
		if (reference instanceof ServiceReferenceImpl) {
			return this.matchCase(((ServiceReferenceImpl<?>) reference).getRegistration().getProperties());
		}
		return this.matchCase(new ServiceReferenceDictionary(reference));
	}

	/**
	 * Filter using a {@code Dictionary}. This {@code Filter} is executed using
	 * the specified {@code Dictionary}'s keys and values. The keys are looked
	 * up in a normal manner respecting case.
	 *
	 * @param dictionary
	 *            The {@code Dictionary} whose key/value pairs are used in the
	 *            match.
	 * @return {@code true} if the {@code Dictionary}'s values match this
	 *         filter; {@code false} otherwise.
	 * @since 1.3
	 */
	@Override
	public boolean matchCase(final Dictionary<String, ?> dictionary) {
		switch (this.op) {
		case AND: {
			final FilterImpl[] filters = (FilterImpl[]) this.value;
			for (final FilterImpl f : filters) {
				if (!f.matchCase(dictionary)) {
					return false;
				}
			}

			return true;
		}

		case OR: {
			final FilterImpl[] filters = (FilterImpl[]) this.value;
			for (final FilterImpl f : filters) {
				if (f.matchCase(dictionary)) {
					return true;
				}
			}

			return false;
		}

		case NOT: {
			final FilterImpl filter = (FilterImpl) this.value;

			return !filter.matchCase(dictionary);
		}

		case SUBSTRING:
		case EQUAL:
		case GREATER:
		case LESS:
		case APPROX: {
			final Object prop = (dictionary == null) ? null : dictionary.get(this.attr);

			return this.compare(this.op, prop, this.value);
		}

		case PRESENT: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("PRESENT(" + this.attr + ")"); //$NON-NLS-1$ //$NON-NLS-2$
			}

			final Object prop = (dictionary == null) ? null : dictionary.get(this.attr);

			return prop != null;
		}
		}

		return false;
	}

	/**
	 * Filter using a {@code Map}. This {@code Filter} is executed using the
	 * specified {@code Map}'s keys and values. The keys are looked up in a
	 * normal manner respecting case.
	 *
	 * @param map
	 *            The {@code Map} whose key/value pairs are used in the match.
	 *            Maps with {@code null} key or values are not supported. A
	 *            {@code null} value is considered not present to the filter.
	 * @return {@code true} if the {@code Map}'s values match this filter;
	 *         {@code false} otherwise.
	 * @since 1.6
	 */
	@Override
	public boolean matches(final Map<String, ?> map) {
		switch (this.op) {
		case AND: {
			final FilterImpl[] filters = (FilterImpl[]) this.value;
			for (final FilterImpl f : filters) {
				if (!f.matches(map)) {
					return false;
				}
			}

			return true;
		}

		case OR: {
			final FilterImpl[] filters = (FilterImpl[]) this.value;
			for (final FilterImpl f : filters) {
				if (f.matches(map)) {
					return true;
				}
			}

			return false;
		}

		case NOT: {
			final FilterImpl filter = (FilterImpl) this.value;

			return !filter.matches(map);
		}

		case SUBSTRING:
		case EQUAL:
		case GREATER:
		case LESS:
		case APPROX: {
			final Object prop = (map == null) ? null : map.get(this.attr);

			return this.compare(this.op, prop, this.value);
		}

		case PRESENT: {
			if (Debug.DEBUG_FILTER) {
				Debug.println("PRESENT(" + this.attr + ")"); //$NON-NLS-1$ //$NON-NLS-2$
			}

			final Object prop = (map == null) ? null : map.get(this.attr);

			return prop != null;
		}
		}

		return false;
	}

	/**
	 * Returns this <code>Filter</code>'s normalized filter string.
	 * <p>
	 * The filter string is normalized by removing whitespace which does not
	 * affect the meaning of the filter.
	 *
	 * @return This <code>Filter</code>'s filter string.
	 */
	private StringBuffer normalize() {
		final StringBuffer sb = new StringBuffer();
		sb.append('(');

		switch (this.op) {
		case AND: {
			sb.append('&');

			final FilterImpl[] filters = (FilterImpl[]) this.value;
			for (final FilterImpl f : filters) {
				sb.append(f.normalize());
			}

			break;
		}

		case OR: {
			sb.append('|');

			final FilterImpl[] filters = (FilterImpl[]) this.value;
			for (final FilterImpl f : filters) {
				sb.append(f.normalize());
			}

			break;
		}

		case NOT: {
			sb.append('!');
			final FilterImpl filter = (FilterImpl) this.value;
			sb.append(filter.normalize());

			break;
		}

		case SUBSTRING: {
			sb.append(this.attr);
			sb.append('=');

			final String[] substrings = (String[]) this.value;

			for (final String substr : substrings) {
				if (substr == null) /* * */ {
					sb.append('*');
				} else /* xxx */ {
					sb.append(encodeValue(substr));
				}
			}

			break;
		}
		case EQUAL: {
			sb.append(this.attr);
			sb.append('=');
			sb.append(encodeValue((String) this.value));

			break;
		}
		case GREATER: {
			sb.append(this.attr);
			sb.append(">="); //$NON-NLS-1$
			sb.append(encodeValue((String) this.value));

			break;
		}
		case LESS: {
			sb.append(this.attr);
			sb.append("<="); //$NON-NLS-1$
			sb.append(encodeValue((String) this.value));

			break;
		}
		case APPROX: {
			sb.append(this.attr);
			sb.append("~="); //$NON-NLS-1$
			sb.append(encodeValue(approxString((String) this.value)));

			break;
		}

		case PRESENT: {
			sb.append(this.attr);
			sb.append("=*"); //$NON-NLS-1$

			break;
		}
		}

		sb.append(')');

		return sb;
	}

	/**
	 * Returns this <code>Filter</code> object's filter string.
	 * <p>
	 * The filter string is normalized by removing whitespace which does not
	 * affect the meaning of the filter.
	 *
	 * @return Filter string.
	 */

	@Override
	public String toString() {
		String result = this.filterString;
		if (result == null) {
			this.filterString = result = this.normalize().toString();
		}
		return result;
	}
}