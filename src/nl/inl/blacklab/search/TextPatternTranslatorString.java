/*******************************************************************************
 * Copyright (c) 2010, 2012 Institute for Dutch Lexicology.
 * All rights reserved.
 *******************************************************************************/
package nl.inl.blacklab.search;

import java.util.List;

import nl.inl.blacklab.index.complex.ComplexFieldUtil;
import nl.inl.util.StringUtil;

/**
 * Translates a TextPattern to a String, for debugging and testing purposes.
 */
public class TextPatternTranslatorString implements TextPatternTranslator<String> {
	@Override
	public String and(String fieldName, List<String> clauses) {
		return "AND(" + StringUtil.join(clauses, ", ") + ")";
	}

	@Override
	public String or(String fieldName, List<String> clauses) {
		return "OR(" + StringUtil.join(clauses, ", ") + ")";
	}

	@Override
	public String property(String fieldName, String propertyName, String altName, TextPattern input) {
		return input.translate(this, ComplexFieldUtil.fieldName(fieldName, propertyName, altName));
	}

	@Override
	public String regex(String fieldName, String value) {
		return "REGEX(" + value + ")";
	}

	@Override
	public String sequence(String fieldName, List<String> clauses) {
		return "SEQ(" + StringUtil.join(clauses, ", ") + ")";
	}

	@Override
	public String docLevelAnd(String fieldName, List<String> clauses) {
		return "DOC-AND(" + StringUtil.join(clauses, ", ") + ")";
	}

	@Override
	public String fuzzy(String fieldName, String value, float similarity, int prefixLength) {
		return "FUZZY(" + value + ", " + similarity + ", " + prefixLength + ")";
	}

	@Override
	public String tags(String fieldName, String elementName) {
		return "TAGS(" + elementName + ")";
	}

	@Override
	public String containing(String fieldName, String containers, String search) {
		return "CONTAINING(" + containers + ", " + search + ")";
	}

	@Override
	public String term(String fieldName, String value) {
		return "TERM(" + value + ")";
	}

	@Override
	public String expand(String clause, boolean expandToLeft, int min, int max) {
		return "EXPAND(" + clause + ", " + expandToLeft + ", " + min + ", " + max + ")";
	}

	@Override
	public String repetition(String clause, int min, int max) {
		return "REP(" + clause + ", " + min + ", " + max + ")";
	}

	@Override
	public String docLevelAndNot(String include, String exclude) {
		return "ANDNOT(" + include + ", " + exclude + ")";
	}

	@Override
	public String wildcard(String fieldName, String value) {
		return "WILDCARD(" + value + ")";
	}

	@Override
	public String prefix(String fieldName, String value) {
		return "PREFIX(" + value + ")";
	}
}