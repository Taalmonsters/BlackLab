/*******************************************************************************
 * Copyright (c) 2010, 2012 Institute for Dutch Lexicology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package nl.inl.blacklab.indexers;

import java.io.Reader;

import org.xml.sax.Attributes;

import nl.inl.blacklab.index.DocIndexerXmlHandlers;
import nl.inl.blacklab.index.HookableSaxHandler.ContentCapturingHandler;
import nl.inl.blacklab.index.HookableSaxHandler.ElementHandler;
import nl.inl.blacklab.index.Indexer;
import nl.inl.blacklab.index.complex.ComplexFieldProperty;

/**
 * Index a FoLiA file for WhiteLab 2.0.
 * For information about FoLiA, see http://proycon.github.io/folia/
 * For information about WhiteLab, see https://github.com/Taalmonsters/WhiteLab
 */
public class DocIndexerWhiteLab2 extends DocIndexerXmlHandlers {

	String xmlid;

	String wordform;

	String pos;

	String lemma;
	
	String phonetic;
	
	String speaker;
	
	boolean sentenceStart = true;
	
	boolean paragraphStart = true;

	boolean lemPosProblemReported = false;
	
	int numPhonAnnotations = 0;

	/**
	 * If we have 1 PoS annotation, use pos tags without a set
	 * attribute. If we have 2, we use pos tags with
	 * set="http://ilk.uvt.nl/folia/sets/frog-mbpos-cgn"
	 */
	int numPosAnnotations = 0;

	/**
	 * If we have 1 lemma annotation, use lemma tags without a set
	 * attribute. If we have 2, we use pos tags with
	 * set="http://ilk.uvt.nl/folia/sets/frog-mblem-nl"
	 */
	int numLemmaAnnotations = 0;

	public DocIndexerWhiteLab2(Indexer indexer, String fileName, Reader reader) {
		super(indexer, fileName, reader);

		// Get handles to the default properties (the main one & punct)
		final ComplexFieldProperty propMain = getMainProperty();
		final ComplexFieldProperty propPunct = getPropPunct();

		// Add some extra properties
		final ComplexFieldProperty propLemma = addProperty("lemma");
		final ComplexFieldProperty propPartOfSpeech = addProperty("pos");
		final ComplexFieldProperty propPhonetic = addProperty("phonetic");
		final ComplexFieldProperty propXmlid = addProperty("xmlid");
		final ComplexFieldProperty propSenStart = addProperty("sentence_start");
		final ComplexFieldProperty propParStart = addProperty("paragraph_start");
		final ComplexFieldProperty propSpeaker = addProperty("sentence_speaker");

		// Doc element: the individual documents to index
		addHandler("/FoLiA", new DocumentElementHandler());

		// PoS annotation metadata: see which annotation we need to use.
		addHandler("pos-annotation", new ElementHandler() {
			@Override
			public void startElement(String uri, String localName,
					String qName, Attributes attributes) {
				super.startElement(uri, localName, qName, attributes);
				numPosAnnotations++;
			}
		});

		// Lemma annotation metadata: see which annotation we need to use.
		addHandler("lemma-annotation", new ElementHandler() {
			@Override
			public void startElement(String uri, String localName,
					String qName, Attributes attributes) {
				super.startElement(uri, localName, qName, attributes);
				numLemmaAnnotations++;
			}
		});

		addHandler("phon-annotation", new ElementHandler() {
			@Override
			public void startElement(String uri, String localName,
					String qName, Attributes attributes) {
				super.startElement(uri, localName, qName, attributes);
				numPhonAnnotations++;
			}
		});

		// Word elements: index as main contents
		addHandler("w", new WordHandlerBase() {

			@Override
			public void startElement(String uri, String localName, String qName,
					Attributes attributes) {
				super.startElement(uri, localName, qName, attributes);
				xmlid = attributes.getValue("xml:id");
				wordform = "";
				pos = "";
				lemma = "";
				phonetic = "";
			}

			@Override
			public void endElement(String uri, String localName, String qName) {
				super.endElement(uri, localName, qName);
				if (wordform.length() > 0) {

					propMain.addValue(wordform);
					propXmlid.addValue(xmlid);
					propPartOfSpeech.addValue(pos);
					propLemma.addValue(lemma);
					
					if (pos.length() == 0 || lemma.length() == 0) {
						if (!lemPosProblemReported) {
							lemPosProblemReported = true;
							System.err.println(
								"Word without Pos (set=http://ilk.uvt.nl/folia/sets/frog-mbpos-cgn) and/or lemma (set=http://ilk.uvt.nl/folia/sets/frog-mblem-nl "
								+ "at " + describePosition());
						}
					}
					
					if (numPhonAnnotations > 0)
						propPhonetic.addValue(phonetic);
					
					if (paragraphStart) {
						propParStart.addValue("true");
						paragraphStart = false;
					} else
						propParStart.addValue("false");
					
					if (sentenceStart) {
						propSenStart.addValue("true");
						sentenceStart = false;
					} else
						propSenStart.addValue("false");
					
					if (speaker != null)
						propSpeaker.addValue(speaker);
					
					propPunct.addValue(" ");
				}
			}
		});

		// lemma element: contains lemma
		addHandler("lemma", new ElementHandler() {
			@Override
			public void startElement(String uri, String localName, String qName,
					Attributes attributes) {
				super.startElement(uri, localName, qName, attributes);
				String set = attributes.getValue("set");
				boolean isSetLess = set == null || set.length() == 0;
				boolean isFrog = !isSetLess && set.equals("http://ilk.uvt.nl/folia/sets/frog-mblem-nl");
				if (numLemmaAnnotations == 2 && isFrog ||
					numLemmaAnnotations == 1 && isSetLess) {
					// If there were 2 lemma annotation meta declarations,
					// we should use the frog ones; if only 1, the ones
					// without a "set" attribute.
					lemma = attributes.getValue("class");
					if (lemma == null)
						lemma = "";
				}
			}
		});

		// pos element: contains part of speech
		addHandler("pos", new ElementHandler() {
			@Override
			public void startElement(String uri, String localName, String qName,
					Attributes attributes) {
				super.startElement(uri, localName, qName, attributes);
				String set = attributes.getValue("set");
				boolean isSetLess = set == null || set.length() == 0;
				boolean isFrog = !isSetLess && set.equals("http://ilk.uvt.nl/folia/sets/frog-mbpos-cgn");
				if (numPosAnnotations == 2 && isFrog ||
					numPosAnnotations == 1 && isSetLess) {
					// If there were 2 pos annotation meta declarations,
					// we should use the frog ones; if only 1, the ones
					// without a "set" attribute.
					pos = attributes.getValue("class");
					if (pos == null)
						pos = "";
				}
			}
		});
		addHandler("ph", new ContentCapturingHandler() {
			@Override
			public void startElement(String uri, String localName, String qName,
					Attributes attributes) {
				super.startElement(uri, localName, qName, attributes);
			}

			@Override
			public void endElement(String uri, String localName, String qName) {
				super.endElement(uri, localName, qName);
				phonetic = getElementContent();
			}
		});

		// t (token) element directly under w (word) element: contains the word form
		addHandler("w/t", new ContentCapturingHandler() {

			/** Tokens with a class attribute are (usually?) the original scanned token before correction,
			 *  so we skip them */
			boolean isOcr;

			@Override
			public void startElement(String uri, String localName, String qName,
					Attributes attributes) {
				super.startElement(uri, localName, qName, attributes);
				isOcr = attributes.getValue("class") != null;
			}

			@Override
			public void endElement(String uri, String localName, String qName) {
				super.endElement(uri, localName, qName);
				if (!isOcr)
					wordform = getElementContent();
			}
		});

		// Sentence tags: index as tags in the content
		addHandler("s", new ElementHandler() {
			@Override
			public void startElement(String uri, String localName, String qName,
					Attributes attributes) {
				sentenceStart = true;
				speaker = attributes.getValue("speaker");
			}

			@Override
			public void endElement(String uri, String localName, String qName) {
				speaker = null;
			}
		});

		// Paragraph tags: index as tags in the content
		addHandler("p", new ElementHandler() {
			@Override
			public void startElement(String uri, String localName, String qName,
					Attributes attributes) {
				paragraphStart = true;
			}
		});

		// <event/> tags: index as tags in the content
		addHandler("event", new ElementHandler() {
			@Override
			public void startElement(String uri, String localName, String qName,
					Attributes attributes) {
				paragraphStart = true;
			}
		});

		// meta elements: metadata fields
		// [NOT USED FOR OPENSONAR..?]
		addHandler("meta", new ContentCapturingHandler() {


			private String metadataFieldName;

			/** Open tag: add metadata field */
			@Override
			public void startElement(String uri, String localName, String qName, Attributes attributes) {
				super.startElement(uri, localName, qName, attributes);
				metadataFieldName = attributes.getValue("id");
			}

			@Override
			public void endElement(String uri, String localName, String qName) {
				super.endElement(uri, localName, qName);
				if (metadataFieldName != null)
					addMetadataField(metadataFieldName, getElementContent());
			}
		});
	}

	/*
	List<String> untokenizedFields = Arrays.asList(
		"Country",
		"LicentieCode",
		"LicenseDetails",
		"CollectionName"
	);
	*/

	@Override
	public void addMetadataField(String name, String value) {

		/*
		// FIXME HACK: See if we need to substitute token-ending characters
		if (untokenizedFields.contains(name)) {
			// Yes; substitute token-ending characters for underscore in these fields!
			value = value.replaceAll("[\\s\\./]", "_");
		}
		*/

		super.addMetadataField(name, value);
	}

	public static void main(String[] args) {
		System.out.println("NL B".replaceAll("[\\s\\./]", "_"));
		System.out.println("NL/B".replaceAll("[\\s\\./]", "_"));
		System.out.println("a.b.c.d".replaceAll("[\\s\\./]", "_"));
	}
}
