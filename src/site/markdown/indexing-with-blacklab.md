# Indexing with BlackLab

This page goes into detail about indexing documents with BlackLab.
For a simple tutorial, see [Adding a new input format](add-input-format.html).

[TOC]

## Indexing documents in a supported format

Start the IndexTool without parameters for help information:

    java -cp BLACKLAB_JAR nl.inl.blacklab.tools.IndexTool
 
To create a new index:

    java -cp BLACKLAB_JAR nl.inl.blacklab.tools.IndexTool create INDEX_DIR INPUT_FILES FORMAT

To add documents to an existing index:

    java -cp BLACKLAB_JAR nl.inl.blacklab.tools.IndexTool add INDEX_DIR INPUT_FILES FORMAT

If you specify a directory as the INPUT_FILES, it will be scanned recursively. You can also specify a file glob (such as \*.xml; single-quote it if you're on Linux so it doesn't get expanded by the shell) or a single file. If you specify a .zip or .tar.gz file, BlackLab will automatically index the contents.

For example, if you have TEI data in /tmp/my-tei/ and want to create an index as a subdirectory of the current directory called "test-index", run the following command:

    java -cp BLACKLAB_JAR nl.inl.blacklab.tools.IndexTool create test-index /tmp/my-tei/ tei

Your data is indexed and placed in a new BlackLab index in the "test-index" directory.

To delete documents from an index:

    java -cp BLACKLAB_JAR nl.inl.blacklab.tools.IndexTool delete INDEX_DIR FILTER_QUERY
    
Here, FILTER_QUERY is a metadata filter query in Lucene query language that matches the documents to delete. Deleting documents and re-adding them can be used to update documents.

## Supported formats

Here's a list of supported input formats:

* folia (a corpus XML format popular in the Netherlands; see https://proycon.github.io/folia/)
* tei (a popular XML format for linguistic resources, including corpora. indexes content inside the 'body' element; assumes part of speech is found in an attribute called 'type'; see http://www.tei-c.org/)
* tei-element-text (a variant of TEI where content inside the 'text' element is indexed)
* tei-pos-function (a variant of TEI where part of speech is in an attribute called 'function')
* sketchxml (a simple XML format based on the word-per-line files that the Sketch Engine and CWB use)
* pagexml (OCR XML format)
* alto (OCR XML format; see http://www.loc.gov/standards/alto/)

Adding support for your own format is not hard. See below, or have a look at [Adding an input format](add-input-format.html). To use your own DocIndexer class with IndexTool, specify the fully-qualified class name as the FORMAT parameters.

## Passing indexing parameters

You can pass parameters to the DocIndexer class to customize its indexing process. This can be done in two ways: as options on the IndexTool command line, or via a properties file.

To pass a parameter as an option on the command line, use three dashes, like this:

    java -cp BLACKLAB_JAR nl.inl.blacklab.tools.IndexTool create ---PARAMNAME PARAMVALUE ...

To pass parameters via a property file, use the --indexparam option:

    java -cp BLACKLAB_JAR nl.inl.blacklab.tools.IndexTool create --indexparam PROPERTYFILE ...

Use the DocIndexer.getParameter(name[, defaultValue]) method to retrieve parameters inside your DocIndexer. For an example of using DocIndexer parameters, see the DocIndexerTeiBase and MetadataFetcherSonarCmdi in the nl.inl.blacklab.indexers package. They use parameters to configure property names and where to fetch metadata from, respectively.

Configuring an external metadata fetcher (see "Metadata from an external source" below) and case- and diacritics sensitivity (see next section) is also done using a indexing parameter for now. Note that this parameter passing mechanism predates the index structure file (see "Configuring the index structure" below) and is likely to be deprecated in favour of that in future versions.

## Configuring case- and diacritics sensitivity per property

You can also configure what "sensitivity alternatives" (case/diacritics sensitivity) to index for each property, using the "PROPNAME_sensitivity" parameter. Accepted values are "i" (both only insensitive), "s" (both only sensitive), "si" (sensitive and insensitive) and "all" (case/diacritics sensitive and insensitive, so 4 alternatives). What alternatives are indexed determines how specifically you can specify the desired sensitivity when searching.

If you don't configure these, BlackLab will pick (hopefully) sane defaults (i.e. word/lemma get "si", punct gets "i", starttag gets "s", others get "i").

## Configuring the index structure

What we call the "index structure" consists of some top-level index information (name, description, etc.), what word-level annotations ("properties") you want to index, what metadata fields there are and how they should be indexed, and more.

By default, a default index structure is determined by BlackLab and the DocIndexer you're using. However, you can influence exactly how your index is created using a customized index structure file. If you specify such an index structure file when creating the index, it will be used as a template for the index metadata file, and so you won't have to specify the index structure file again when updating your index later; all the information is now in the index metadata file. It is possible to edit the index metadata file manually as well, but use caution, because it might break something.

Here's a commented example of indextemplate.json (double-slash comments in JSON files are allowed by BlackLab):

    // indextemplate.json - indexer options (template for the index's indexmetadata.json)
    {
      // Display name for the index and short description
      // (not used by BlackLab. None of the display name or description values
      //  are used by BlackLab directly, but applications can retrieve them if they want)
      "displayName": "OpenSonar",
      "description": "The OpenSonar corpus.",
     
      // About the fields in this index
      // (defaults are set by indexer if not specified)
      "fieldInfo": {
        "namingScheme": "DEFAULT",   // ..or "NO_SPECIAL_CHARS" (the alternate naming scheme,
                                     //  which can be used to avoid problems with e.g. Solr)
                                     // (if omitted, DEFAULT is used)
        "titleField":  "title",  // (detected if omitted; may be used by application to display
                                 //  document titles)
        "authorField": "author", // (may be used by application to display document author)
        "dateField":   "date",   // (may be used by application to display document date)
        "pidField":    "id",     // (may be used by application to uniquely refer to documents;
                                 //  may be used by (future versions of) BlackLab to directly 
                                 //  update documents without the client having to manually delete
                                 //  the previous version)
        "defaultAnalyzerName": "DEFAULT",   // The type of analyzer to use for metadata fields
                                            // by default (DEFAULT|whitespace|standard|nontokenizing)
        "contentViewable": false, // is the user allowed to retrieve whole documents? 
        "documentFormat": "",     // (not used by BlackLab. may be used by application to 
                                  //  e.g. select which XSLT to use)
        
        "unknownValue": "unknown",   // what value to index if field value is unknown [unknown]
        "unknownCondition": "NEVER", // When is a field value considered unknown?
                                     // (other options: MISSING, EMPTY, MISSING_OR_EMPTY) [NEVER]

        "metadataFields": {
            
          "author": {
            "displayName": "author",
            "description": "The author of the document.",
            "group": "authorRelated",     // can be used to group fields in interface
            "type": "tokenized",          // ..or text, numeric, untokenized [tokenized]
            "analyzer": "default",        // ..(or whitespace|standard|nontokenizing) [default]
            "unknownValue": "unknown",    // overrides default unknownValue for this field
            "unknownCondition": "MISSING_OR_EMPTY" // overrides default unknownCondition for this field
          }
        },
        "complexFields": {
          "contents": {
            "mainProperty": "word",     // used for concordances; contains char. offsets
            "displayName": "contents",  // may be used by application
            "description": "The text contents of the document.",  // may be used by application
            "noForwardIndexProps": ""   // space-separated list of property names that shouldn't
                                        // get a forward index [""]
          }
        }
      }
    }

### When and how to disable the forward index for a property

By default, all properties get a forward index. The forward index is the complement to Lucene's reverse index, and can 
quickly answer the question "what value appears in position X of document Y?". This functionality is used to generate
snippets (such as for keyword-in-context (KWIC) views), to sort and group based on context words (such as sorting on the word left of the hit) and will in the future be used to speed up certain query types.

However, forward indices take up a lot of disk space and can take up a lot of memory, and they are not always needed for every 
property. You should probably have a forward index for at least the word and punct properties, and for any property you'd like to sort/group on or that you use heavily in searching. But if you add a property that is only used in certain special cases, you can decide to disable the forward index for that property. You can do this by adding the property name to the "noForwardIndexProps" space-separated list in the indextemplate.json file shown above.

A note about forward indices and indexing multiple values at a single corpus position: as of right now, the forward index will only store the first value indexed at any position. We would like to expand this so that it is possible to quickly retrieve all values indexed at a corpus position, but that is not the case now.

## Indexing documents in a custom format

See also [Adding an input format](add-input-format.html).

If you have text in a format that isn't supported by BlackLab yet, you will have to create a DocIndexer class to support the format. You can use the DocIndexer classes supplied with BlackLab (see the nl.inl.blacklab.indexers package) for reference, but we'll highlight the most important features here.

### Indexing word-annotated XML

If you have an XML format in which each word has its own XML tag, containing any annotations, you should derive your DocIndexer class from DocIndexerXmlHandlers. This class allows you to add 'hooks' for handling different XML elements.

(for a practical example of the following, see [Adding an input format](add-input-format.html))

The constructor of your indexing class should call the superclass constructor, declare any annotations (e.g. lemma, pos) you're going to index and finally add hooks (called handlers) for each XML element you want to do something with.

Declaring annotations (called "properties" in BlackLab) is done using the DocIndexerXmlHandlers.addProperty(String). Store the result in a final variable so you can access it from your custom handlers. Note that the "main property" (usually called "word") and the "punct" property (whitespace and punctuation between words) have already been created by DocIndexerXmlHandlers; retrieve them using the getMainProperty() and getPropPunct() methods.

Adding a handler is done using the DocIndexerXmlHandlers.addHandler(String, ElementHandler) method. The first parameter is an xpath-like expression that indicates the element to handle. The expression looks like xpath but is very limited: only element names separated by slashes; it may start with either a single (absolute path) or a double (relative path) slash. DocIndexerXmlHandlers defines several default ElementHandlers: ElementHandler (does nothing but keep track of whether we're inside this element), DocumentElementHandler (creates and adds document to the index), several metadata handlers that deal with different types of metadata elements (assuming the document contains its own metadata - see below for external metadata), InlineTagHandler (adds inline tags from the content to the index, such as &lt;p&gt;, &lt;s&gt; (sentence), &lt;b&gt;), several word handlers (indexes words and whitespace/punctuation between words). You can also easily create or derive your own, usually as an anonymous inner class that overrides the startElement() and endElement() methods.

You need to add a handler for your document element (signifying the start and end of your logical documents; probably just use DocumentElementHandler), your word element (signifying a word to index; probably derive from WordHandlerBase), and any inline tags you wish to index (probably just use InlineTagHandler). Optionally, you may want to add a simple ElementHandler for your "body" tag, if you wish to restrict what part of the document is actually indexed; in this case, you should expand your word and inline tag handlers to check that you're inside this body element before processing the matched element. 

A word handler derived from DefaultWordHandler might retrieve lemma and part of speech from the attributes of the start tag and add them to the properties you declared at the top of your DocIndexer-constructor. You can do this using the ComplexFieldProperty.addValue() method. DefaultWordHandler takes care of adding values to the standard properties word and punct. For this, DefaultWordHandler assumes the word is simply the word element's text content. DefaultWordHandler also stores the character positions before and after the word (which are needed if you want to highlight in the original XML).

An important note about adding values to properties: it is crucial that you call ComplexFieldProperty.addValue() to each property an equal number of times! Each time you call addValue(), you move that property to the next corpus position, but other properties do not automatically move to the next corpus position; it is up to you to make sure all properties stay at the same corpus position. If a property has no value at a certain position, just add an empty string. 

You should probably call consumeCharacterContent(), which clears the buffer of captured text content in the document, at the start of a document (or at the start of the body element, if you handle that separately). This prevents the first punct value containing already captured text content you don't want. Similarly, before storing the document, you should add one last punct value (using consumeCharacterContent() to get the value to store), so the last bit of whitespace/punctuation isn't skipped. BlackLab assumes that there's always an extra "dummy" token containing only the last bit of whitespace/punctuation.

The [tutorial](add-input-format.html) develops a simple TEI DocIndexer using the above techniques.

### Multiple values at one position, position gaps and adding property values at an earlier position

The ComplexFieldProperty.addValue(String) method adds a value to a property ("annotation layer") at the next corpus position. Sometimes you may want to add multiple values at a single corpus position, or you may want to skip a number of corpus positions. This can be done using the ComplexFieldProperty.addValue(String, Integer) method; the second parameter is the increment compared to the previous value. The default value for the increment is 1, meaning each value is indexed at the next corpus position.

To add multiple values to a single corpus position, only use the default increment of 1 for the first value you want to add at this position; for all subsequent values at this position, use an increment of 0. Note: if the value you added first was the empty string, adding the next value with an increment of 0 will overwrite this empty string. This can be convenient if you're not sure whether you want to add any values at a particular location, but you want to make sure the property stays at the correct corpus position regardless.

To skip a number of corpus positions when adding a value, use an increment that is higher than 1. So to skip one position (and therefore leave a "gap" one wide), use an increment of 2.

Finally, you may sometimes wish to add values to an earlier corpus position. Say you're at position 100, and you want to add a value to position 50. You can do so using the ComplexFieldProperty.addValueAtPosition(String, Integer) method. The first token has position 0.

### Storing extra information with property values, using payloads

It is possible to add payloads to property values. When calling addProperty() at the start of the constructor, make sure to use the version that takes a boolean called 'includePayloads', and set it to true. Then use ComplexFieldProperty.addPayload(). You can use null if a particular value has no payload. There's also a addPayloadAtIndex() method to add payloads some time after adding the value itself, but that requires knowing the index in the value list of the value you want to add a payload for, so you should store this index when you add the value.

One example of using payloads can be seen in DocIndexerXmlHandlers.InlineTagHandler. When you use InlineTagHandler to index an inline element, say a sentence tag, BlackLab will add a value (or several values, if the element has attributes) to the built-in 'starttag' property. When it encounters the end tag, it wil update the start tag value with a payload indication the element length. This is used when searching to determine what matches occur inside certain XML tags.

### Indexing non-XML file types

If your input files are not XML or are not tokenized and annotated per word, you have two options: convert them into a tokenized, per-word annotated format, or index them directly.

Indexing them directly is not covered here, but involves deriving from DocIndexerAbstract or implementing the DocIndexer interface yourself. If you need help with this, please [contact us](mailto:jan.niestadt@inl.nl).

## Metadata 

### In-document metadata

Some documents contain metadata within the document. You usually want to index these as fields with your document, so you can filter on them later. You do this by adding a handler for the appropriate XML element.

There's a few helper classes for in-document metadata handling. MetadataElementHandler assumes the matched element name is the name of your metadata field and the character content is the value. MetadataAttributesHandler stores all the attributes from the matched element as metadata fields. MetadataNameValueAttributeHandler assumes the matched element has a name attribute and a value attribute (the attribute names can be specified in the constructor) and stores those as metadata fields. You can of course easily add your own handler classes to this if they don't suit your particular style of metadata.

### Metadata from an external source

Sometimes, documents link to external metadata sources, usually using an ID.

The MetadataFetcher is instantiated by DocIndexerXmlHandlers.getMetadataFetcher(), based on the metadataFetcherClass indexer parameter (see "Passing indexing parameters" above). This class is instantiated with the DocIndexer as a parameter, and the addMetadata() method is called just before adding a document to the index. Your particular MetadataFetcher can inspect the document to find the appropriate ID, fetch the metadata (e.g. from a file, database or webservice) and add it to the document using the DocIndexerXmlHandlers.addMetadataField() method.

Also see the two MetadataFetcher examples in nl.inl.blacklab.indexers.




