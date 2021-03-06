# Change Log

## Improvements in HEAD

## Improvements up to v1.3.6

### Fixed
* Content store growing larger than 2 GB caused an integer overflow.
* Fixed bug with element matching in DocIndexers.
* Fixed rare bug while storing document in ContentStoreDirFixedBlock.

## Improvements up to v1.3.5

### Fixed
* BLSpanOrQuery would occasionally miss valid hits due to a
  bug in the advanceStartPoint() method.

### Changed
* Switched to JavaCC 6.x.
* Added default unknown condition and value to indextemplate.json, so you can specify what to do if a metadata field value is missing without specifying it for each field separately.

## Improvements up to v1.3.4

### Fixed
* Lone carriage return characters in JSON output were not escaped;
  Windows line endings were escaped as a single \\n.

## Improvements up to v1.3.3

### Fixed
* Indices with old terms file format (pre-1.3) produced empty concordances.

## Improvements up to v1.3.2

### Fixed
* Query rewrite bug when combining identical clauses with different repetitions,
  i.e. \[pos="AA.*"\]\[pos="AA.*"\]* --> \[pos="AA.*"\]+
* Throw a descriptive error if an index contains no fields.

### Changed
* Some small code quality improvements, like using .isEmpty() instead of .size() == 0.
* Added -javadoc and -sources JARs to Maven build, in preparation for publishing to Maven Central.
* Added distributionManagement section for deploying to OSSRH staging area. 

## Improvements up to v1.3.1

### Fixed
* Bug in SpanQueryAnd which caused incorrect hits to be reported.

## Changed
* Special OSX and Windows files are skipped, even if they occur inside archives.
* Added new default content store format "fixedblock", that improves space re-use when updating documents. 

## Improvements up to v1.3

### Added
* Searcher now implements Closeable, so it can be used with the try-with-resources statement.
* You can specify that properties should not get a forward index using the complexField property "noForwardIndexProps" (space-separated list of property names) in indextemplate.json.

### Fixed
* Forward index terms is no longer limited to around 2 GB.

### Changed

## Improvements up to v1.2.1

### Fixed
* Queries containing only a document filter (metadata filter) would return incorrect results.

## Improvements up to v1.2.0

### Changed
* Switched build from Ant to Maven, and added generating a project site with javadocs, reports, etc.
* Using less memory by switching some Maps in the forward index to the gs-collections one.
* Updated to Lucene 5.2.1.
* Added Maven project site, available at http://inl.github.io/BlackLab/
* Removed Lucene query parser for corpus queries.
* Keep tag end position in payload of start tag, which results in much faster tag searches.
* Rewrote many slower queries to make them (much) faster. Particularly searches with "not" parts and "containing"/"within" should be faster.
* Sped up "containing", "within", and other such filter queries.
* TextPatternAnd was renamed and expanded to TextPatternAndNot. TextPatternAnd is still available as a synonym, but has been deprecated.
* Added TextPatternFilterNGrams to speed up queries of the form: []{2,3} containing "water" (or the "within" equivalent).
* Added BLSpans.advanceStartPosition(target) to "skip" within a document.
* Commons-compress (used for reading .gz files) is statically linked now.
* Limited token length to 1000 (really long tokens would cause problems otherwise).

### Fixed
* Empty version file causes NullPointerException.
* Missing manifest file causes NullPointerException.
* ContentStoreDir contained dependencies on the default encoding.
* A number of subtle search bugs.
* Opening an index by passing a symbolic link throws an exception.
* Miscellaneous small fixes.

## Improvements up to v1.1.0
* Upgraded from Lucene 3.6 to Lucene 4.2. This should speed up regular expression searching, among other things. The required Lucene 4 modules are: core, highlighter, queries, queryparser, analyzers-common. Thanks to Marc Kemps-Sneijders from the Meertens Institute for the code submission!
* The awkwardly-named classes RandomAccessGroup(s) were renamed to HitGroup(s). Also, DocGrouper was renamed to DocGroups to match this naming scheme. The old versions are still around but have been deprecated.
* HitPropValue classes now need a Hits object to properly serialize/deserialize their values in a way that doesn't break after re-indexing.
* Manual object construction was replaced with method calls where possible, for convenience, speed and ease of refactoring. Examples: use Hits.window() instead of new HitsWindow(); use Hits.groupedBy() instead of new ResultsGrouper(); use DocResults.groupedBy() instead of new DocGroups(). (code to the HitGroups/DocGroups APIs instead of to the concrete type ResultsGrouper/DocGrouper). Same for DocResults.
* HitGroups now iterates over HitGroup (used to iterate over Group)
* If you just want to query documents (not find hits for a pattern), use Searcher.queryDocuments(Query) (returns DocResults without hits).
* Preferably use Hits.sortedBy() (returns a new Hits instance) instead of Hits.sort() (modifies Hits instance). In a future version, we want Hits to become immutable to facilitate caching in a multithreaded application. Note that although you get a new Hits instance, the hits themselves are not all copied (no need, because the Hit class is now immutable).
* LuceneQueryParser.allowLeadingWildcard now defaults to false like in Lucene itself. Call LuceneQueryParser.setAllowLeadingWildcard() to change the setting.
* If you want to control how indexing errors are handled, subclass IndexListener and override the errorOccurred() method. This method receives information on what file couldn’t be indexed and why.
* Visibility for some internal classes and methods has been reduced from public to package-private to trim the public API footprint, promoting ease-of-use and facilitating future refactoring. This should not affect applications. If it does affect you, please let me know.
* Some other methods have been renamed, are no longer needed, etc. and have been deprecated. Deprecated methods state the preferred alternative in the @deprecated Javadoc directive.


## Improvements up to v1.0

### Features
* Sorting/grouping on multiple properties now works correctly. Use HitPropertyMultiple.
* You can now sort/group (case-/accent-) sensitively or insensitively.
* You can now easily get a larger snippet of context around a single hit (say, 100 words before and after the hit). Call Hits.getConcordance(String, Hit, int) for this purpose.
* Indexing classes now work using element handlers, making them much more readable. Supporting a new file format has become simpler as a result of this. TEI P4/P5 and FoLiA indexing classes are included with BlackLab now. See nl.inl.blacklab.indexers package.
* It is now possible to delete documents from your index. The forward indices will reuse the free space for new documents.
* The Hits class should be thread-safe now. This makes several things possible: paging through hits without re-executing the query and quickly displaying the first few hits while a background thread fetches the rest. You can even display a running counter while hits are being fetched.
* nl.inl.blacklab.tools.IndexTool is a new generic indexing tool. It is command-line utility that lets you create new indices, add documents to them and delete them again. Indexing can be customized via commandline parameters and/or a properties file. Pass --help for more info.
* QueryTool, the command-line search tool and demonstration program, has been improved with many little features, including a performance test mode.
* Long-running queries may be interrupted using Thread.interrupt(); this will stop the gathering of hits and return control to the caller.
* Hacked in (very) experimental SRU CQL (Contextual Query Language) support. Still needs a bit more love though. :-)

### Performance-/memory-related
* Concordances (for KWIC views) are constructed using the forward indices now (including the one for the new ‘punct’ property, containing punctuation and whitespace between words – if you created your own indexer, it pays to update it to include this property). Before they were constructed using the content store, but this method is much faster and more disk cache friendly. 
* Startup speed has been improved, and there is an option to automatically “warm up” forward indices (i.e. prime the disk cache) in a background thread on startup. Enable this by calling Searcher.setAutoWarmForwardIndices(true); before constructing a Searcher object. This may become the default behaviour in future versions.
* Applications have more control over the maximum number of hits to retrieve, and the maximum hits to count. “Unlimited” is also an option. By default, no more than 1M hits are retrieved, but all hits are counted.
* Several types of queries (notably, phrase searches) have been sped up using the ‘guarantee’ methods in BLSpans to determine when certain operations can be skipped.
* Several other small improvements in performance and memory use.

### Other
* Opening the BlackLab index should now be done using Searcher.open() instead of directly through constructor. See the [https://github.com/INL/BlackLab/commit/d1d1b71ca8d5ef2aea25eab5a6e12b7e51cf5f65 commit message] for the rationale.
* Several superfluous methods were deprecated to simplify the API. The Javadoc will indicate why a method was deprecated and what alternative you should use. Deprecated methods will be removed in the next major version.
* Many small bugs fixed, comments added and code structure improved.
