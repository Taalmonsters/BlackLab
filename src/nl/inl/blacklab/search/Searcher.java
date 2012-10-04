/*******************************************************************************
 * Copyright (c) 2010, 2012 Institute for Dutch Lexicology.
 * All rights reserved.
 *******************************************************************************/
package nl.inl.blacklab.search;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nl.inl.blacklab.externalstorage.ContentAccessorContentStore;
import nl.inl.blacklab.externalstorage.ContentStore;
import nl.inl.blacklab.externalstorage.ContentStoreDir;
import nl.inl.blacklab.externalstorage.ContentStoreDirAbstract;
import nl.inl.blacklab.externalstorage.ContentStoreDirUtf8;
import nl.inl.blacklab.externalstorage.ContentStoreDirZip;
import nl.inl.blacklab.forwardindex.ForwardIndex;
import nl.inl.blacklab.highlight.XmlHighlighter;
import nl.inl.blacklab.highlight.XmlHighlighter.HitSpan;
import nl.inl.blacklab.index.complex.ComplexFieldUtil;
import nl.inl.blacklab.search.lucene.SpansFiltered;
import nl.inl.blacklab.search.lucene.TextPatternTranslatorSpanQuery;
import nl.inl.util.ExUtil;
import nl.inl.util.Utilities;
import nl.inl.util.VersionFile;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermFreqVector;
import org.apache.lucene.index.TermPositionVector;
import org.apache.lucene.index.TermVectorOffsetInfo;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.highlight.QueryTermExtractor;
import org.apache.lucene.search.highlight.WeightedTerm;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.Spans;
import org.apache.lucene.store.FSDirectory;

/**
 * The main interface into the BlackLab library. The Searcher object is instantiated with an open
 * Lucene IndexReader and accesses that index through special methods.
 *
 * The Searcher object knows how to access the original contents of indexed fields, either because
 * the field is a stored field in the Lucene index, or because it knows where else the content can
 * be found (such as in fixed-length-encoding files, for fast random access).
 *
 * Searcher is thread-safe: a single instance may be shared to perform a number of simultaneous
 * searches.
 */
public class Searcher implements Closeable {
	private static final String DEFAULT_CONTENTS_FIELD = "contents";

	/**
	 * The collator to use for sorting. Defaults to English collator.
	 */
	private Collator collator = Collator.getInstance(new Locale("en", "GB"));

	/**
	 * The collator to use for sorting hit context. Defaults to a per-word version of the English
	 * collator. Per-word means that for example "cat dog" is sorted before "catapult". The normal
	 * collator would ignore the space and sort "catapult" first.
	 */
	private Collator perWordCollator = Utilities.getPerWordCollator(collator);

	/**
	 * ContentAccessors tell us how to get a field's content: - if there is no contentaccessor: get
	 * it from the Lucene index (stored field) - from an external source (file, database) if it's
	 * not (because the content is very large and/or we want faster random access to the content
	 * than a stored field can provide)
	 */
	private Map<String, ContentAccessor> contentAccessors = new HashMap<String, ContentAccessor>();

	/**
	 * ForwardIndices allow us to quickly find what token occurs at a specific position. This speeds
	 * up grouping and sorting. By default, there will be one forward index, on the "contents"
	 * field.
	 */
	private Map<String, ForwardIndex> forwardIndices = new HashMap<String, ForwardIndex>();

	/**
	 * The Lucene index reader
	 */
	private IndexReader indexReader;

	/**
	 * The Lucene IndexSearcher, for dealing with non-Span queries (for per-document scoring)
	 */
	private IndexSearcher indexSearcher;

	/**
	 * Should we close the IndexReader in our close() method, or will the client do that?
	 */
	private boolean responsibleForClosingReader;

	/**
	 * Number of words around a hit (default value 5)
	 */
	private int concordanceContextSize = 5;

	/**
	 * Should we close the default forward index (on the field "contents")?
	 */
	private boolean responsibleForClosingForwardIndex = false;

	/**
	 * Should we close the default content store (on the field "contents")?
	 */
	private boolean responsibleForClosingContentStore = false;

	/**
	 * @deprecated use Searcher(indexDir) instead
	 * @param indexReader
	 */
	@Deprecated
	public Searcher(IndexReader indexReader) {
		this(indexReader, null);
	}

	/**
	 * Construct a Searcher object. Note that using this constructor, the caller is responsible for
	 * closing the Lucene index after closing the Searcher object.
	 *
	 * @deprecated use Searcher(indexDir) instead
	 * @param indexReader
	 *            the Lucene index reader
	 * @param forwardIndex
	 *            the forward index, or null if none
	 */
	@Deprecated
	public Searcher(IndexReader indexReader, ForwardIndex forwardIndex) {
		this.indexReader = indexReader;
		registerForwardIndex(DEFAULT_CONTENTS_FIELD, forwardIndex);
		init();
	}

	/**
	 * @deprecated use Searcher(indexDir) instead
	 *
	 * @param indexDir
	 * @param forwardIndex
	 * @throws CorruptIndexException
	 * @throws IOException
	 */
	@Deprecated
	public Searcher(File indexDir, ForwardIndex forwardIndex) throws CorruptIndexException,
			IOException {
		indexReader = IndexReader.open(FSDirectory.open(indexDir));
		registerForwardIndex(DEFAULT_CONTENTS_FIELD, forwardIndex);
		responsibleForClosingReader = true;
		init();
	}

	/**
	 * Construct a Searcher object. Note that using this constructor, the Searcher is responsible
	 * for opening and closing the Lucene index, forward index and content store.
	 *
	 * Automatically detects and uses forward index and content store if available.
	 *
	 * @param indexDir
	 *            the index directory
	 * @throws CorruptIndexException
	 * @throws IOException
	 */
	public Searcher(File indexDir) throws CorruptIndexException, IOException {
		if (!VersionFile.isTypeVersion(indexDir, "blacklab", "1"))
			throw new RuntimeException("BlackLab index has wrong type or version! "
					+ VersionFile.report(indexDir));

		// Detect and open forward index, if any
		File forwardIndexDir = new File(indexDir, "forward");
		if (forwardIndexDir.exists()) {
			registerForwardIndex(DEFAULT_CONTENTS_FIELD, new ForwardIndex(forwardIndexDir));
			responsibleForClosingForwardIndex = true;
		}

		// Detect and open the default ContentStore (field "contents"). This
		// resides in a directory called "xml" (old) or "cs_contents" (new).
		File contentsContentStoreDirOld = new File(indexDir, "xml");
		File contentsContentStoreDirNew = new File(indexDir, "cs_contents");
		File dir = contentsContentStoreDirOld.exists() ? contentsContentStoreDirOld
				: contentsContentStoreDirNew;
		if (dir.exists()) {
			registerContentStore(DEFAULT_CONTENTS_FIELD, openContentStore(dir));
			responsibleForClosingContentStore = true;
		}

		// Open Lucene index
		indexReader = IndexReader.open(FSDirectory.open(indexDir));
		responsibleForClosingReader = true;

		init();
	}

	/**
	 * Construct the IndexSearcher and set the maximum boolean clause count a little higher.
	 */
	private void init() {
		indexSearcher = new IndexSearcher(indexReader);

		// Make sure large wildcard/regex expansions succeed
		BooleanQuery.setMaxClauseCount(100000);
	}

	/**
	 * Finalize the Searcher object. This closes the IndexSearcher and (depending on the constructor
	 * used) may also close the index reader.
	 */
	@Override
	public void close() {
		try {
			indexSearcher.close();
			if (responsibleForClosingReader)
				indexReader.close();
			if (responsibleForClosingForwardIndex) {
				// NOTE: we only close the "main" forward index. Additional
				// forward indices may have been added by the client, but it is
				// responsible for closing them.
				ForwardIndex mainForwInd = forwardIndices.get(DEFAULT_CONTENTS_FIELD);
				if (mainForwInd != null)
					mainForwInd.close();
			}
			if (responsibleForClosingContentStore) {
				// Close the default content store (because we opened it)
				ContentAccessor ca = contentAccessors.get(DEFAULT_CONTENTS_FIELD);
				if (ca instanceof ContentAccessorContentStore) {
					ContentStore contentStore = ((ContentAccessorContentStore) ca)
							.getContentStore();
					contentStore.close();
				}
			}
		} catch (IOException e) {
			throw ExUtil.wrapRuntimeException(e);
		}
	}

	/**
	 * Retrieve a Lucene Document object from the index.
	 *
	 * @param doc
	 *            the document id
	 * @return the Lucene Document
	 */
	public Document document(int doc) {
		try {
			return indexReader.document(doc);
		} catch (Exception e) {
			throw ExUtil.wrapRuntimeException(e);
		}
	}

	/**
	 * Filter a Spans object (collection of hits), only keeping hits in a subset of documents. All
	 * other hits are discarded.
	 *
	 * @param spans
	 *            the collection of hits
	 * @param docIdSet
	 *            the documents for which to keep the hits
	 * @return the resulting Spans
	 */
	public Spans filterDocuments(Spans spans, DocIdSet docIdSet) {
		try {
			return new SpansFiltered(spans, docIdSet);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Filter a Spans object (collection of hits), only keeping hits in a subset of documents,
	 * described by a Filter. All other hits are discarded.
	 *
	 * @param spans
	 *            the collection of hits
	 * @param filter
	 *            the document filter
	 * @return the resulting Spans
	 */
	public Spans filterDocuments(Spans spans, Filter filter) {
		try {
			return new SpansFiltered(spans, filter, indexReader);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Filter a Spans object (collection of hits), only keeping hits in a single document.
	 *
	 * @param spans
	 *            the collection of hits
	 * @param docId
	 *            the document we want hits in
	 * @return the resulting Spans
	 */
	public Spans filterSingleDocument(Spans spans, final int docId) {
		return filterDocuments(spans, new SingleDocIdSet(docId));
	}

	/**
	 * Filter a Hits object, only keeping hits in a single document.
	 *
	 * @param hits
	 *            the collection of hits
	 * @param id
	 *            the document we want hits in
	 * @return the resulting Spans
	 */
	public Hits filterSingleDocument(Hits hits, int id) {
		Hits hitsFiltered = new Hits(this, hits.getDefaultConcordanceField());
		hitsFiltered.setConcordanceStatus(hits.getConcordanceField(), hits.getConcordanceType());
		for (Hit hit : hits) {
			if (hit.doc == id)
				hitsFiltered.add(hit);
		}
		return hitsFiltered;
	}

	/**
	 * Find hits in a field. Returns a Lucene Spans object.
	 *
	 * @param spanQuery
	 *            the query
	 * @return the Results object
	 * @throws BooleanQuery.TooManyClauses
	 *             if a wildcard or regular expression term is overly broad
	 */
	public Spans findSpans(SpanQuery spanQuery) throws BooleanQuery.TooManyClauses {
		return findSpans(spanQuery, null);
	}

	/**
	 * Find hits in a field. Returns a Lucene Spans object.
	 *
	 * Uses a Filter to only search certain documents and ignore others.
	 *
	 * @param spanQuery
	 *            the query
	 * @param filter
	 *            determines which documents to search
	 * @return the results object
	 * @throws BooleanQuery.TooManyClauses
	 *             if a wildcard or regular expression term is overly broad
	 */
	public Spans findSpans(SpanQuery spanQuery, Filter filter) throws BooleanQuery.TooManyClauses {
		try {
			spanQuery = (SpanQuery) spanQuery.rewrite(indexReader);
			Spans spans = spanQuery.getSpans(indexReader);
			if (filter != null)
				spans = new SpansFiltered(spans, filter, indexReader);

			return spans;
		} catch (BooleanQuery.TooManyClauses e) {
			// re-throw so the application can catch it
			throw e;
		} catch (Exception e) {
			throw ExUtil.wrapRuntimeException(e);
		}
	}

	/**
	 * Find hits for a pattern in a field. Returns a Lucene Spans object.
	 *
	 * Uses a Filter to only search certain documents and ignore others.
	 *
	 * @param field
	 *            which field to find the pattern in
	 * @param pattern
	 *            the pattern to find
	 * @param filter
	 *            determines which documents to search
	 * @return the results object
	 * @throws BooleanQuery.TooManyClauses
	 *             if a wildcard or regular expression term is overly broad
	 */
	public Spans findSpans(String field, TextPattern pattern, Filter filter)
			throws BooleanQuery.TooManyClauses {
		// Convert to SpanQuery
		TextPatternTranslatorSpanQuery spanQueryTranslator = new TextPatternTranslatorSpanQuery();
		SpanQuery spanQuery = pattern.translate(spanQueryTranslator, field);

		return findSpans(spanQuery, filter);
	}

	/**
	 * Find hits for a pattern in a field. Returns a Lucene Spans object.
	 *
	 * @param field
	 *            which field to find the pattern in
	 * @param pattern
	 *            the pattern to find
	 * @return the results object
	 * @throws BooleanQuery.TooManyClauses
	 *             if a wildcard or regular expression term is overly broad
	 */
	public Spans findSpans(String field, TextPattern pattern) throws BooleanQuery.TooManyClauses {
		return findSpans(field, pattern, null);
	}

	/**
	 * Find hits for a pattern in a field.
	 *
	 * @param field
	 *            which field to find the pattern in
	 * @param pattern
	 *            the pattern to find
	 * @return the hits found
	 * @throws BooleanQuery.TooManyClauses
	 *             if a wildcard or regular expression term is overly broad
	 */
	public Hits find(String field, TextPattern pattern) throws BooleanQuery.TooManyClauses {
		return new Hits(this, findSpans(field, pattern), field);
	}

	/**
	 * Execute a Span query and filter the results.
	 *
	 * @param field
	 *            field to use for sorting and displaying resulting concordances.
	 * @param query
	 *            the query to execute
	 * @param filter
	 *            determines which documents to search
	 * @return the hits found
	 * @throws BooleanQuery.TooManyClauses
	 *             if a wildcard or regular expression term is overly broad
	 */
	public Hits find(String field, SpanQuery query, Filter filter)
			throws BooleanQuery.TooManyClauses {
		return new Hits(this, findSpans(query, filter), field);
	}

	/**
	 * Execute a Span query.
	 *
	 * @param field
	 *            field to use for sorting and displaying resulting concordances.
	 * @param query
	 *            the query to execute
	 * @return the hits found
	 * @throws BooleanQuery.TooManyClauses
	 *             if a wildcard or regular expression term is overly broad
	 */
	public Hits find(String field, SpanQuery query) throws BooleanQuery.TooManyClauses {
		return new Hits(this, findSpans(query), field);
	}

	/**
	 * Find hits for a pattern in a field.
	 *
	 * @param field
	 *            field to use for sorting and displaying resulting concordances.
	 * @param pattern
	 *            the pattern to find
	 * @param filter
	 *            determines which documents to search
	 * @return the hits found
	 * @throws BooleanQuery.TooManyClauses
	 *             if a wildcard or regular expression term is overly broad
	 */
	public Hits find(String field, TextPattern pattern, Filter filter)
			throws BooleanQuery.TooManyClauses {
		return new Hits(this, findSpans(field, pattern, filter), field);
	}

	/**
	 * Find matching documents and their scores for a pattern.
	 *
	 * You can pass in both a SpanQuery or a regular Query.
	 *
	 * @param q
	 * @return object that can iterate over matching docs and provide their scores. NOTE: null can
	 *         be returned if there were no matches!
	 * @throws BooleanQuery.TooManyClauses
	 *             if a wildcard or regular expression term is overly broad
	 */
	public Scorer findDocScores(Query q) {
		try {
			IndexSearcher s = new IndexSearcher(indexReader); // TODO: cache in field?
			try {
				// OLD:
				// q = q.rewrite(indexReader);
				// Weight w = q.weight(s);
				Weight w = s.createNormalizedWeight(q);
				Scorer sc = w.scorer(indexReader, true, false);
				return sc;
			} finally {
				s.close();
			}
		} catch (IOException e) {
			throw ExUtil.wrapRuntimeException(e);
		}
	}

	/**
	 * Find the top-scoring documents.
	 *
	 * @param q
	 *            the query
	 *
	 * @param n
	 *            number of top documents to return
	 * @return the documents
	 */
	public TopDocs findTopDocs(Query q, int n) {
		try {
			IndexSearcher s = new IndexSearcher(indexReader);
			try {
				return s.search(q, n);
			} finally {
				s.close();
			}
		} catch (IOException e) {
			throw ExUtil.wrapRuntimeException(e);
		}
	}

	/**
	 * Get character positions from word positions.
	 *
	 * Places character positions in the same arrays as the word positions were specified in.
	 *
	 * NOTE: If any illegal word positions are specified (say, past the end of the document), a sane
	 * default value is chosen (in this case, the last character of the last word found).
	 *
	 * @param doc
	 *            the document from which to find character positions
	 * @param fieldName
	 *            the field from which to find character positions
	 * @param startsOfWords
	 *            word positions for which we want starting character positions (i.e. the position
	 *            of the first letter of that word)
	 * @param endsOfWords
	 *            word positions for which we want ending character positions (i.e. the position of
	 *            the last letter of that word)
	 */
	public void getCharacterOffsets(int doc, String fieldName, int[] startsOfWords,
			int[] endsOfWords) {
		getCharacterOffsets(doc, fieldName, startsOfWords, endsOfWords, true);
	}

	/**
	 * Get character positions from word positions.
	 *
	 * Places character positions in the same arrays as the word positions were specified in.
	 *
	 * @param doc
	 *            the document from which to find character positions
	 * @param fieldName
	 *            the field from which to find character positions
	 * @param startsOfWords
	 *            word positions for which we want starting character positions (i.e. the position
	 *            of the first letter of that word)
	 * @param endsOfWords
	 *            word positions for which we want ending character positions (i.e. the position of
	 *            the last letter of that word)
	 * @param fillInDefaultsIfNotFound
	 *            if true, if any illegal word positions are specified (say, past the end of the
	 *            document), a sane default value is chosen (in this case, the last character of the
	 *            last word found). Otherwise, throws an exception.
	 */
	public void getCharacterOffsets(int doc, String fieldName, int[] startsOfWords,
			int[] endsOfWords, boolean fillInDefaultsIfNotFound) {
		TermFreqVector termFreqVector = getTermFreqVector(doc, fieldName);
		if (!(termFreqVector instanceof TermPositionVector)) {
			throw new RuntimeException("Field has no character position information!");
		}
		TermPositionVector termPositionVector = (TermPositionVector) termFreqVector;

		int numStarts = startsOfWords.length;
		int numEnds = endsOfWords.length;
		int total = numStarts + numEnds;
		int[] done = new int[total]; // NOTE: array is automatically initialized to zeroes!

		// Vraag het array van terms (voor reconstructie text)
		String[] docTerms = termPositionVector.getTerms();

		// Determine lowest and highest word position we'd like to know something about.
		// This saves a little bit of time for large result sets.
		int minP = -1, maxP = -1;
		for (int i = 0; i < startsOfWords.length; i++) {
			if (startsOfWords[i] < minP || minP == -1)
				minP = startsOfWords[i];
			if (startsOfWords[i] > maxP)
				maxP = startsOfWords[i];
		}
		for (int i = 0; i < endsOfWords.length; i++) {
			if (endsOfWords[i] < minP || minP == -1)
				minP = endsOfWords[i];
			if (endsOfWords[i] > maxP)
				maxP = endsOfWords[i];
		}
		if (minP < 0 || maxP < 0)
			throw new RuntimeException("Can't determine min and max positions");

		// Verzamel concordantiewoorden uit term vector
		int found = 0;
		int lowestPos = -1, highestPos = -1;
		int lowestPosFirstChar = -1, highestPosLastChar = -1;
		for (int k = 0; k < docTerms.length && found < total; k++) {
			int[] positions = termPositionVector.getTermPositions(k);
			TermVectorOffsetInfo[] offsetInfo = termPositionVector.getOffsets(k);
			for (int l = 0; l < positions.length; l++) {
				int p = positions[l];

				// Keep track of the lowest and highest char pos, so
				// we can fill in the character positions we didn't find
				if (p < lowestPos || lowestPos == -1) {
					lowestPos = p;
					lowestPosFirstChar = offsetInfo[l].getStartOffset();
				}
				if (p > highestPos) {
					highestPos = p;
					highestPosLastChar = offsetInfo[l].getEndOffset();
				}

				// We've calculated the min and max word positions in advance, so
				// we know we can skip this position if it's outside the range we're interested in.
				// (Saves a little time for large result sets)
				if (p < minP || p > maxP)
					continue;

				for (int m = 0; m < numStarts; m++) {
					if (done[m] == 0 && p == startsOfWords[m]) {
						done[m] = 1;
						startsOfWords[m] = offsetInfo[l].getStartOffset();
						found++;
					}
				}
				for (int m = 0; m < numEnds; m++) {
					if (done[numStarts + m] == 0 && p == endsOfWords[m]) {
						done[numStarts + m] = 1;
						endsOfWords[m] = offsetInfo[l].getEndOffset();
						found++;
					}
				}

				// NOTE: we might be tempted to break here if found == total,
				// but that would foul up our calculation of highestPostLastChar and
				// lowestPosFirstChar.
			}
		}
		if (found < total) {
			if (!fillInDefaultsIfNotFound)
				throw new RuntimeException("Could not find all character offsets!");

			if (lowestPosFirstChar < 0 || highestPosLastChar < 0)
				throw new RuntimeException("Could not find default char positions!");

			for (int m = 0; m < numStarts; m++) {
				if (done[m] == 0)
					startsOfWords[m] = lowestPosFirstChar;
			}
			for (int m = 0; m < numEnds; m++) {
				if (done[numStarts + m] == 0)
					endsOfWords[m] = highestPosLastChar;
			}
		}
	}

	/**
	 * Get character positions from a list of hits.
	 *
	 * @param doc
	 *            the document from which to find character positions
	 * @param fieldName
	 *            the field from which to find character positions
	 * @param hits
	 *            the hits for which we wish to find character positions
	 * @return a list of HitSpan objects containing the character positions for the hits.
	 */
	public List<HitSpan> getCharacterOffsets(int doc, String fieldName, List<Hit> hits) {
		int[] starts = new int[hits.size()];
		int[] ends = new int[hits.size()];
		Iterator<Hit> hitsIt = hits.iterator();
		for (int i = 0; i < starts.length; i++) {
			Hit hit = hitsIt.next(); // hits.get(i);
			starts[i] = hit.start;
			ends[i] = hit.end - 1; // end actually points to the first word not in the hit, so
									// subtract one
		}

		getCharacterOffsets(doc, fieldName, starts, ends, true);

		List<HitSpan> hitspans = new ArrayList<HitSpan>(starts.length);
		for (int i = 0; i < starts.length; i++) {
			hitspans.add(new HitSpan(starts[i], ends[i]));
		}
		return hitspans;
	}

	/**
	 * Get the contents of a field from a Lucene Document.
	 *
	 * This takes into account that some fields are stored externally in a fixed-length encoding
	 * instead of in the Lucene index.
	 *
	 * @param d
	 *            the Document
	 * @param fieldName
	 *            the name of the field
	 * @return the field content
	 */
	public String getContent(Document d, String fieldName) {
		ContentAccessor ca = contentAccessors.get(fieldName);
		String content;
		if (ca == null) {
			// No special content accessor set; assume a stored field
			content = d.get(fieldName);
		} else {
			// Content accessor set. Use it to retrieve the content.
			content = ca.getSubstringFromDocument(d, -1, -1);
		}
		return content;
	}

	/**
	 * Get the Lucene index reader we're using.
	 *
	 * @return the Lucene index reader
	 */
	public IndexReader getIndexReader() {
		return indexReader;
	}

	/**
	 * Get all the terms in the index with low edit distance from the supplied term
	 *
	 * @param field
	 *            the field to search in
	 * @param searchTerms
	 *            search terms
	 * @param similarity
	 *            measure of similarity we need
	 * @return the set of terms in the index that are close to our search term
	 * @throws BooleanQuery.TooManyClauses
	 *             if the expansion resulted in too many terms
	 */
	public Set<String> getMatchingTermsFromIndex(String field, Collection<String> searchTerms,
			float similarity) {
		boolean doFuzzy = true;
		if (similarity >= 0.99f) {
			// Exact match; don't use fuzzy query (slow)
			Set<String> result = new HashSet<String>();
			for (String term : searchTerms) {
				if (termOccursInIndex(new Term(field, term)))
					result.add(term);
			}
			return result;
		}

		BooleanQuery q = new BooleanQuery();
		for (String s : searchTerms) {
			FuzzyQuery fq = new FuzzyQuery(new Term(field, s), similarity);
			q.add(fq, Occur.SHOULD);
		}

		try {
			Query rewritten = q.rewrite(indexReader);
			WeightedTerm[] wts = QueryTermExtractor.getTerms(rewritten);
			Set<String> terms = new HashSet<String>();
			for (WeightedTerm wt : wts) {
				if (doFuzzy || searchTerms.contains(wt.getTerm())) {
					terms.add(wt.getTerm());
				}
			}
			return terms;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Get a number of substrings from a certain field in a certain document.
	 *
	 * For larger documents, this is faster than retrieving the whole content first and then cutting
	 * substrings from that.
	 *
	 * @param d
	 *            the document
	 * @param fieldName
	 *            the field
	 * @param starts
	 *            start positions of the substring we want
	 * @param ends
	 *            end positions of the substring we want; correspond to the starts array.
	 * @return the substrings
	 */
	private String[] getSubstringsFromDocument(Document d, String fieldName, int[] starts,
			int[] ends) {
		ContentAccessor ca = contentAccessors.get(fieldName);
		String[] content;
		if (ca == null) {
			// No special content accessor set; assume a stored field
			String fieldContent = d.get(fieldName);
			content = new String[starts.length];
			for (int i = 0; i < starts.length; i++) {
				content[i] = fieldContent.substring(starts[i], ends[i]);
			}
		} else {
			// Content accessor set. Use it to retrieve the content.
			content = ca.getSubstringsFromDocument(d, starts, ends);
		}
		return content;
	}

	/**
	 * Get a term frequency vector for a certain field in a certain document.
	 *
	 * @param doc
	 *            the document
	 * @param fieldName
	 *            the field
	 * @return the term vector
	 */
	private TermFreqVector getTermFreqVector(int doc, String fieldName) {
		try {
			// Vraag de term position vector van de contents van dit document op
			// NOTE: je kunt ook alle termvectors in 1x opvragen. Kan sneller zijn.
			TermFreqVector termFreqVector = indexReader.getTermFreqVector(doc, fieldName);
			if (termFreqVector == null) {
				throw new RuntimeException("Field has no term vector!");
			}
			return termFreqVector;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Get all words between the specified start and end positions from the term vector.
	 *
	 * NOTE: this may return an array of less than the size requested, if the document ends before
	 * the requested end position.
	 *
	 * @param doc
	 *            doc id
	 * @param fieldName
	 *            the index field from which to use the term vector
	 * @param start
	 *            start position (first word we want to request)
	 * @param end
	 *            end position (last word we want to request)
	 * @return the words found, in order
	 */
	public String[] getWordsFromTermVector(int doc, String fieldName, int start, int end) {
		try {
			// Vraag de term position vector van de contents van dit document op
			// NOTE: je kunt ook alle termvectors in 1x opvragen. Kan sneller zijn.
			TermPositionVector termPositionVector = (TermPositionVector) indexReader
					.getTermFreqVector(doc, fieldName);
			if (termPositionVector == null) {
				throw new RuntimeException("Field " + fieldName + " has no TermPositionVector");
			}

			// Vraag het array van terms (voor reconstructie text)
			String[] docTerms = termPositionVector.getTerms();

			// Verzamel concordantiewoorden uit term vector
			String[] concordanceWords = new String[end - start + 1];
			int numFound = 0;
			for (int k = 0; k < docTerms.length; k++) {
				int[] positions = termPositionVector.getTermPositions(k);
				for (int l = 0; l < positions.length; l++) {
					int p = positions[l];
					if (p >= start && p <= end) {
						concordanceWords[p - start] = docTerms[k];
						numFound++;
					}
				}
				if (numFound == concordanceWords.length)
					return concordanceWords;
			}
			if (numFound < concordanceWords.length) {
				// throw new
				// RuntimeException("Not all words found ("+numFound+" out of "+concordanceWords.length+")");
				String[] partial = new String[numFound];
				for (int i = 0; i < numFound; i++) {
					partial[i] = concordanceWords[i];
					if (partial[i] == null) {
						throw new RuntimeException("Not all words found (" + numFound + " out of "
								+ concordanceWords.length
								+ "); missing words in the middle of concordance!");
					}
				}
				return partial;
			}
			return concordanceWords;
		} catch (Exception e) {
			throw ExUtil.wrapRuntimeException(e);
		}
	}

	/**
	 * Get all words between the specified start and end positions from the term vector.
	 *
	 * NOTE: this may return an array of less than the size requested, if the document ends before
	 * the requested end position.
	 *
	 * @param doc
	 *            doc id
	 * @param fieldName
	 *            the index field from which to use the term vector
	 * @param start
	 *            start position (first word we want to request)
	 * @param end
	 *            end position (last word we want to request)
	 * @return the words found, in order
	 */
	public List<String[]> getWordsFromTermVector(int doc, String fieldName, int[] start, int[] end) {
		try {
			// Get the term position vector of the requested field
			TermPositionVector termPositionVector = (TermPositionVector) indexReader
					.getTermFreqVector(doc, fieldName);
			if (termPositionVector == null) {
				throw new RuntimeException("Field " + fieldName + " has no TermPositionVector");
			}

			// Get the array of terms (for reconstructing text)
			String[] docTerms = termPositionVector.getTerms();

			List<String[]> results = new ArrayList<String[]>(start.length);
			for (int i = 0; i < start.length; i++) {
				// Gather concordance words from term vector
				String[] concordanceWords = new String[end[i] - start[i] + 1];
				int numFound = 0;
				for (int k = 0; k < docTerms.length; k++) {
					int[] positions = termPositionVector.getTermPositions(k);
					for (int l = 0; l < positions.length; l++) {
						int p = positions[l];
						if (p >= start[i] && p <= end[i]) {
							concordanceWords[p - start[i]] = docTerms[k];
							numFound++;
						}
					}
					if (numFound == concordanceWords.length)
						break;
				}
				if (numFound < concordanceWords.length) {
					// throw new
					// RuntimeException("Not all words found ("+numFound+" out of "+concordanceWords.length+")");
					String[] partial = new String[numFound];
					for (int j = 0; j < numFound; j++) {
						partial[j] = concordanceWords[j];
						if (partial[j] == null) {
							throw new RuntimeException("Not all words found (" + numFound
									+ " out of " + concordanceWords.length
									+ "); missing words in the middle of concordance!");
						}
					}
					results.add(partial);
				} else
					results.add(concordanceWords);
			}
			return results;
		} catch (Exception e) {
			throw ExUtil.wrapRuntimeException(e);
		}
	}

	/**
	 * Highlight field content with the specified hits.
	 *
	 * Uses &lt;hl&gt;&lt;/hl&gt; tags to highlight the content.
	 *
	 * @param docId
	 *            document to highlight a field from
	 * @param fieldName
	 *            field to highlight
	 * @param hits
	 *            the hits
	 * @return the highlighted content
	 */
	public String highlightContent(int docId, String fieldName, Hits hits) {
		// Get the field content
		Document doc = document(docId);
		String content = getContent(doc, fieldName);

		// Nothing to highlight?
		if (hits == null || hits.size() == 0)
			return content;

		// Iterate over the concordances and display
		XmlHighlighter hl = new XmlHighlighter();

		// Find the character offsets
		List<HitSpan> hitspans = getCharacterOffsets(docId, fieldName, hits.getHits());

		return hl.highlight(content, hitspans);
	}

	static private Pattern altoProblem = Pattern
			.compile("</hl>(<String [^>]+>)<hl></hl>(</String>)<hl>");

	/**
	 * Highlight field content with the specified hits.
	 *
	 * Uses &lt;hl&gt;&lt;/hl&gt; tags to highlight the content.
	 *
	 * @param docId
	 *            document to highlight a field from
	 * @param fieldName
	 *            field to highlight
	 * @param hits
	 *            the hits
	 * @return the highlighted content
	 */
	public String highlightContentAlto(int docId, String fieldName, Hits hits) {
		// Get the field content
		Document doc = document(docId);
		String content = getContent(doc, fieldName);

		// Nothing to highlight?
		if (hits == null || hits.size() == 0)
			return content;

		// Iterate over the concordances and display
		XmlHighlighter hl = new XmlHighlighter();
		hl.setRemoveEmptyHlTags(false);

		// Find the character offsets
		List<HitSpan> hitspans = getCharacterOffsets(docId, fieldName, hits.getHits());

		String result = hl.highlight(content, hitspans);

		// Hack to fix the alto problem (content is in attributes, so doesn't get highlighted)
		// @@@ TODO: generalise the highlighting code to better understand XML structure so this
		// isn't necessary anymore.
		Matcher m = altoProblem.matcher(result);
		return m.replaceAll("$1$2");
	}

	/**
	 * Determine the concordance strings for a number of concordances, given the relevant character
	 * positions.
	 *
	 * Every concordance requires four character positions: concordance start and end, and hit start
	 * and end. Visualising it ('fox' is the hit word):
	 *
	 * [A] the quick brown [B] fox [C] jumps over the [D]
	 *
	 * The startsOfWords array contains the [A] and [B] positions for each concordance. The
	 * endsOfWords array contains the [C] and [D] positions for each concordance.
	 *
	 * @param doc
	 *            the Lucene document number
	 * @param fieldName
	 *            name of the field
	 * @param startsOfWords
	 *            the array of starts of words ([A] and [B] positions)
	 * @param endsOfWords
	 *            the array of ends of words ([C] and [D] positions)
	 * @param resultList
	 *            the list to add the arrays of concordance strings to. Each concordance is an array
	 *            of 3 strings: left context, hit text, right context.
	 */
	public void makeFieldConcordances(int doc, String fieldName, int[] startsOfWords,
			int[] endsOfWords, List<Hit> resultList) {
		// Determine starts and ends
		int n = startsOfWords.length / 2;
		int[] starts = new int[n];
		int[] ends = new int[n];
		for (int i = 0, j = 0; i < startsOfWords.length; i += 2, j++) {
			starts[j] = startsOfWords[i];
			ends[j] = endsOfWords[i + 1];
		}

		// Retrieve 'em all
		Document d = document(doc);
		String[] content = getSubstringsFromDocument(d, fieldName, starts, ends);

		// Cut 'em up
		Iterator<Hit> resultListIt = resultList.iterator();
		for (int i = 0, j = 0; i < startsOfWords.length; i += 2, j++) {
			// Put the concordance in the Hit object
			Hit hit = resultListIt.next();
			int absLeft = startsOfWords[i];
			int absRight = endsOfWords[i + 1];
			int relHitLeft = startsOfWords[i + 1] - absLeft;
			int relHitRight = endsOfWords[i] - absLeft;
			String currentContent = content[j];
			hit.conc = new String[] { currentContent.substring(0, relHitLeft),
					currentContent.substring(relHitLeft, relHitRight),
					currentContent.substring(relHitRight, absRight - absLeft) };
		}
	}

	protected void registerForwardIndex(String fieldName, ForwardIndex fi) {
		forwardIndices.put(fieldName, fi);
	}

	/**
	 * Register a content accessor.
	 *
	 * This tells the Searcher how the content of different fields may be accessed. This is used for
	 * making concordances, for example. Some fields are stored in the Lucene index, while others
	 * may be stored on the file system, a database, etc.
	 *
	 * @param contentAccessor
	 */
	protected void registerContentAccessor(ContentAccessor contentAccessor) {
		contentAccessors.put(contentAccessor.getFieldName(), contentAccessor);
	}

	/**
	 * Register a ContentStore as a content accessor.
	 *
	 * This tells the Searcher how the content of different fields may be accessed. This is used for
	 * making concordances, for example. Some fields are stored in the Lucene index, while others
	 * may be stored on the file system, a database, etc.
	 *
	 * A ContentStore is a filesystem-based way to access the contents.
	 *
	 * @param fieldName
	 *            the field for which this is the content accessor
	 * @param contentStore
	 *            the ContentStore object by which to access the content
	 *
	 */
	public void registerContentStore(String fieldName, ContentStore contentStore) {
		registerContentAccessor(new ContentAccessorContentStore(fieldName, contentStore));
	}

	/**
	 * Test if a term occurs in the index
	 *
	 * @param term
	 *            the term
	 * @return true iff it occurs in the index
	 */
	public boolean termOccursInIndex(Term term) {
		try {
			return indexReader.docFreq(term) > 0;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Set the collator used for sorting.
	 *
	 * The default collator is a space-correct English one.
	 *
	 * @param collator
	 *            the collator
	 */
	public void setCollator(Collator collator) {
		this.collator = collator;
	}

	/**
	 * Get the collator being used for sorting.
	 *
	 * @return the collator
	 */
	public Collator getCollator() {
		return collator;
	}

	/**
	 * Set the collator used for sorting context.
	 *
	 * The default collator is a space-correct English one.
	 *
	 * @param collator
	 *            the collator
	 */
	public void setPerWordCollator(Collator collator) {
		perWordCollator = collator;
	}

	/**
	 * Get the collator being used for sorting context.
	 *
	 * @return the collator
	 */
	public Collator getPerWordCollator() {
		return perWordCollator;
	}

	/**
	 * Retrieves the concordance information (left, hit and right context) for a number of hits in
	 * the same document.
	 *
	 * NOTE: the slowest part of this is getting the character offsets (retrieving large term
	 * vectors takes time; subsequent hits from the same document are significantly faster,
	 * presumably because of caching)
	 *
	 * @param hits
	 *            the hits in question
	 * @param fieldName
	 *            Lucene index field to make conc for
	 * @param wordsAroundHit
	 *            number of words left and right of hit to fetch
	 * @param useTermVector
	 *            true if we want to use term vector for the concordances
	 */
	private void makeConcordancesSingleDoc(List<Hit> hits, String fieldName, int wordsAroundHit,
			boolean useTermVector) {
		if (hits.size() == 0)
			return;
		int doc = hits.get(0).doc;

		// Determine the first and last word of the concordance, as well as the
		// first and last word of the actual hit inside the concordance.
		int arrayLength = hits.size() * 2;
		int[] startsOfWords = new int[arrayLength];
		int[] endsOfWords = new int[arrayLength];
		int startEndArrayIndex = 0;
		for (Hit hit : hits) {
			if (hit.doc != doc)
				throw new RuntimeException(
						"makeConcordancesSingleDoc() called with hits from several docs");

			int hitStart = hit.start;
			int hitEnd = hit.end - 1;

			int start = hitStart - wordsAroundHit;
			if (start < 0)
				start = 0;
			int end = hitEnd + wordsAroundHit;

			startsOfWords[startEndArrayIndex] = start;
			startsOfWords[startEndArrayIndex + 1] = hitStart;
			endsOfWords[startEndArrayIndex] = hitEnd;
			endsOfWords[startEndArrayIndex + 1] = end;

			startEndArrayIndex += 2;
		}

		if (useTermVector) {
			getConcordancesFromTermVector(doc, fieldName, startsOfWords, endsOfWords, hits);
		} else {
			// Get the relevant character offsets (overwrites the startsOfWords and endsOfWords
			// arrays)
			getCharacterOffsets(doc, fieldName, startsOfWords, endsOfWords, true);

			try {
				// Make all the concordances
				makeFieldConcordances(doc, fieldName, startsOfWords, endsOfWords, hits);
			} finally {
				// logger.debug("after conc: " + timer.elapsed());
			}
		}
	}

	/**
	 * Build concordances from the term vector.
	 *
	 * The array layout is a little unusual. If this is a typical concordance:
	 *
	 * <code>[A] left context [B] hit text [C] right context [D]</code>
	 *
	 * the positions A-D for each of the concordances should be in the arrays startsOfWords and
	 * endsOfWords as follows:
	 *
	 * <code>starsOfWords: A1, B1, A2, B2, ...</code> <code>endsOfWords: C1, D1, C2, D2, ...</code>
	 *
	 * @param doc
	 *            the document to build concordances from
	 * @param fieldName
	 *            the field to build concordances from
	 * @param startsOfWords
	 *            contains, for each concordance, the starting word position of the left context and
	 *            for the hit
	 * @param endsOfWords
	 *            contains, for each concordance, the ending word position of the hit and for the
	 *            left context
	 * @param resultsList
	 */
	private void getConcordancesFromTermVector(int doc, String fieldName, int[] startsOfWords,
			int[] endsOfWords, List<Hit> resultsList) {
		int n = startsOfWords.length / 2;
		int[] startsOfSnippets = new int[n];
		int[] endsOfSnippets = new int[n];
		for (int i = 0, j = 0; i < startsOfWords.length; i += 2, j++) {
			startsOfSnippets[j] = startsOfWords[i];
			endsOfSnippets[j] = endsOfWords[i + 1];
		}

		// Get all the words from the term vector
		List<String[]> words;
		ForwardIndex forwardIndex = forwardIndices.get(fieldName);
		if (forwardIndex != null) {
			// We have a forward index for this field. Use it.
			Document d = document(doc);
			int fiid = Integer.parseInt(d.get(ComplexFieldUtil.fieldName(DEFAULT_CONTENTS_FIELD,
					"fiid")));
			words = forwardIndex.retrieveParts(fiid, startsOfSnippets, endsOfSnippets);
		} else {
			// We don't have a forward index. Use the term vector.
			words = getWordsFromTermVector(doc, fieldName, startsOfSnippets, endsOfSnippets);
		}

		// Build the actual concordances
		Iterator<String[]> wordsIt = words.iterator();
		Iterator<Hit> resultsListIt = resultsList.iterator();
		for (int j = 0; j < n; j++) {
			int concPart = 0;
			StringBuilder[] concBuilder = new StringBuilder[] { new StringBuilder(),
					new StringBuilder(), new StringBuilder() };

			concBuilder[2].append(" ");
			String[] theseWords = wordsIt.next(); // words.get(j);
			for (int i = 0; i < theseWords.length; i++) {
				int c = i + startsOfWords[j * 2];

				if (c < startsOfWords[j * 2 + 1]) {
					concPart = 0;
				} else if (c > endsOfWords[j * 2]) {
					concPart = 2;
				} else
					concPart = 1;
				if (concBuilder[concPart].length() == 0)
					concBuilder[concPart].append(theseWords[i]);
				else
					concBuilder[concPart].append(" ").append(theseWords[i]);
			}
			concBuilder[0].append(" ");

			// Put the concordance in the Hit object
			Hit hit = resultsListIt.next(); // resultsList.get(j);
			hit.conc = new String[] { concBuilder[0].toString(), concBuilder[1].toString(),
					concBuilder[2].toString() };
		}
	}

	/**
	 * Indicate if we'd like to use the forward index, if one is present.
	 *
	 * Mainly useful for debugging purposed.
	 *
	 * @deprecated not really necessary anymore; forward index is always used if available
	 *
	 * @param useForwardIndex
	 *            true if we'd like to use a forward index, false if not.
	 */
	@Deprecated
	public void setUseForwardIndex(boolean useForwardIndex) {
		// this.useForwardIndex = useForwardIndex;
	}

	/**
	 * Retrieve concordancs for a list of hits.
	 *
	 * Concordances are the hit words 'centered' with a certain number of context words around them.
	 *
	 * The concordances are placed inside the Hit objects, in the conc[] array.
	 *
	 * The size of the left and right context (in words) may be set using
	 * Searcher.setConcordanceContextSize().
	 *
	 * @param fieldName
	 *            field to use for building concordances
	 * @param useTermVector
	 *            if true, builds the concordance from the term vector (Lucene index). Used for
	 *            sorting/grouping.
	 * @param hits
	 *            the hits for which to retrieve concordances
	 */
	public void retrieveConcordances(String fieldName, boolean useTermVector, List<Hit> hits) {
		// Group hits per document
		Map<Integer, List<Hit>> hitsPerDocument = new HashMap<Integer, List<Hit>>();
		for (Hit key : hits) {
			List<Hit> hitsInDoc = hitsPerDocument.get(key.doc);
			if (hitsInDoc == null) {
				hitsInDoc = new ArrayList<Hit>();
				hitsPerDocument.put(key.doc, hitsInDoc);
			}
			hitsInDoc.add(key);
		}
		for (List<Hit> l : hitsPerDocument.values()) {
			makeConcordancesSingleDoc(l, fieldName, concordanceContextSize, useTermVector);
		}
	}

	/**
	 * Get the context size used for building concordances
	 *
	 * @return the context size
	 */
	public int getConcordanceContextSize() {
		return concordanceContextSize;
	}

	/**
	 * Set the context size to use for building concordances
	 *
	 * @param concordanceContextSize
	 *            the context size
	 */
	public void setConcordanceContextSize(int concordanceContextSize) {
		this.concordanceContextSize = concordanceContextSize;
	}

	/**
	 * Do we have a forward index, and is it enabled?
	 *
	 * @return true if we're using one, false if not
	 * @deprecated not used
	 */
	@Deprecated
	public boolean isUsingForwardIndex() {
		return forwardIndices.get(DEFAULT_CONTENTS_FIELD) != null;
	}

	/**
	 * Factory method to create a directory content store.
	 *
	 * @param indexXmlDir
	 *            the content store directory
	 * @return the content store
	 */
	public ContentStore getContentStoreDir(File indexXmlDir, boolean create) {
		VersionFile vf = ContentStoreDirAbstract.getStoreTypeVersion(indexXmlDir);
		String type = vf.getType();
		if (type.equals("utf8zip"))
			return new ContentStoreDirZip(indexXmlDir, create);
		if (type.equals("utf8"))
			return new ContentStoreDirUtf8(indexXmlDir, create);
		if (type.equals("utf16"))
			return new ContentStoreDir(indexXmlDir, create);
		throw new RuntimeException("Unknown content store type " + type);
	}

	/**
	 * Factory method to create a directory content store.
	 *
	 * @param indexXmlDir
	 *            the content store directory
	 * @return the content store
	 */
	public ContentStore openContentStore(File indexXmlDir) {
		return getContentStoreDir(indexXmlDir, false);
	}

	// /**
	// * Get all the terms in the index with low edit distance from the supplied term
	// * @param term search term
	// * @param similarity measure of similarity we need
	// * @return the set of terms in the index that are close to our search term
	// */
	// public Set<String> getMatchingTermsFromIndex(Term term, float similarity)
	// {
	// boolean doFuzzy = true;
	// if (similarity == 1.0f)
	// {
	// // NOTE: even when we don't want to have fuzzy suggestions, we still
	// // use a FuzzyQuery, because a TermQuery isn't checked against the index
	// // on rewrite, so we won't know if it actually occurs in the index.
	// doFuzzy = false;
	// similarity = 0.75f;
	// }
	//
	// FuzzyQuery fq = new FuzzyQuery(term, similarity);
	// //TermQuery fq = new TermQuery(term);
	// try
	// {
	// Query rewritten = fq.rewrite(indexReader);
	// WeightedTerm[] wts = QueryTermExtractor.getTerms(rewritten);
	// Set<String> terms = new HashSet<String>();
	// for (WeightedTerm wt : wts)
	// {
	// if (doFuzzy || wt.getTerm().equals(term.text()))
	// {
	// terms.add(wt.getTerm());
	// }
	// }
	// return terms;
	// }
	// catch (IOException e)
	// {
	// throw new RuntimeException(e);
	// }
	// }

}