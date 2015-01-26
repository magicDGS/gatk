package org.broadinstitute.hellbender.engine;

import htsjdk.tribble.*;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.IndexFeatureFile;
import org.broadinstitute.hellbender.utils.GenomeLoc;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Manages traversals and queries over sources of Features, which are metadata associated with a location
 * on the genome in a format supported by our file parsing framework, Tribble. Examples of Features are
 * VCF/BCF records and hapmap records.
 *
 * Two basic operations are available on this data source:
 *
 * -Iteration over all Features in this data source, unbounded by intervals
 * -Targeted queries by one interval at a time. This requires that the file has been indexed using
 *  the bundled tool IndexFeatureFile, and that Features in the file are sorted in increasing order
 *  of start position within each contig.
 *
 * This class uses a caching scheme that is optimized for the common access pattern of queries over
 * intervals with gradually increasing start positions. It optimizes for this use case by pre-fetching
 * records immediately following each interval during a query and caching them. Performance will
 * suffer if the access pattern is random, involves queries over intervals with DECREASING start
 * positions instead of INCREASING start positions, or involves lots of very large jumps forward on
 * the genome or lots of contig switches.
 *
 * @param <T> The type of Feature returned by this data source
 */
public class FeatureDataSource<T extends Feature> implements GATKDataSource<T>, AutoCloseable {

    /**
     * File backing this data source. Used mainly for error messages.
     */
    private final File featureFile;

    /**
     * Feature reader used to retrieve records from our file
     */
    private final AbstractFeatureReader<T, ?> featureReader;

    /**
     * Tribble codec used by our reader to decode the records in our file
     */
    private final FeatureCodec<T, ?> codec;

    /**
     * Iterator representing an open traversal over this data source initiated via a call to iterator()
     * (null if there is no open traversal). We need this to ensure that each iterator is properly closed,
     * and to enforce the constraint (required by Tribble) that we never have more than one iterator open
     * over our feature reader.
     */
    private CloseableTribbleIterator<T> currentIterator;

    /**
     * Cache containing Features from recent queries. This is guaranteed to start at the start position
     * of the most recent query, but will typically end well after the end of the most recent query.
     * Designed to improve performance of the common access pattern involving multiple queries across
     * nearby intervals with gradually increasing start positions.
     */
    private final FeatureCache<T> queryCache;

    /**
     * When we experience a cache miss (ie., a query interval not fully contained within our cache) and need
     * to re-populate the Feature cache from disk to satisfy a query, this controls the number of extra bases
     * AFTER the end of our interval to fetch. Should be sufficiently large so that typically a significant number
     * of subsequent queries will be cache hits (ie., query intervals fully contained within our cache) before
     * we have another cache miss and need to go to disk again.
     */
    private final int queryLookaheadBases;

    /**
     * An (optional) logical name assigned to this data source. May be null.
     */
    private final String name;

    /**
     * True if the file backing this data source has an accompanying index file, false if it doesn't
     */
    private final boolean hasIndex;

    /**
     * Default value for queryLookaheadBases, if none is specified. This is designed to be large enough
     * so that in typical usage (ie., query intervals with gradually increasing start locations) there will
     * be a substantial number of cache hits between cache misses, reducing the number of times we need to
     * repopulate the cache from disk.
     */
    private static final int DEFAULT_QUERY_LOOKAHEAD_BASES = 1000;

    /**
     * FeatureCache: helper class to manage the cache of Feature records used during query operations.
     *
     * Strategy is to pre-fetch a large number of records AFTER each query interval that produces
     * a cache miss. This optimizes for the use case of intervals with gradually increasing start
     * positions, as many subsequent queries will find their records wholly contained in the cache
     * before we have another cache miss. Performance will be poor for random/non-localized access
     * patterns, or intervals with decreasing start positions.
     *
     * Usage:
     * -Test whether each query interval is a cache hit via {@link #cacheHit(GenomeLoc)}
     *
     * -If it is a cache hit, trim the cache to the start position of the interval (discarding records that
     *  end before the start of the new interval) via {@link #trimToNewStartPosition(long)}, then retrieve
     *  records up to the desired endpoint using {@link #getCachedFeaturesUpToStopPosition(long)}.
     *
     * -If it is a cache miss, reset the cache using {@link #fill(Iterator, GenomeLoc)}, pre-fetching
     *  a large number of records after the query interval in addition to those actually requested.
     *
     * @param <CACHED_FEATURE> Type of Feature record we are caching
     */
    protected static class FeatureCache<CACHED_FEATURE extends Feature> {

        /**
         * Our cache of Features, optimized for insertion/removal at both ends.
         */
        private final Deque<CACHED_FEATURE> cache;

        /**
         * Our cache currently contains Feature records from this contig
         */
        private String cachedContig;

        /**
         * Our cache currently contains records overlapping the region beginning at this start position
         */
        private long cacheStart;

        /**
         * Our cache currently contains records overlapping the region ending at this stop position
         */
        private long cacheStop;

        /**
         * Initial capacity of our cache (will grow by doubling if needed)
         */
        private static final int INITIAL_CAPACITY = 1024;

        /**
         * When we trim our cache to a new start position, this is the maximum number of
         * Features we expect to need to place into temporary storage for the duration of
         * the trim operation. Performance only suffers slightly if our estimate is wrong.
         */
        private static final int EXPECTED_MAX_OVERLAPPING_FEATURES_DURING_CACHE_TRIM = 128;

        /**
         * Create an initially-empty FeatureCache with default initial capacity
         */
        public FeatureCache() {
            cache = new ArrayDeque<>(INITIAL_CAPACITY);
        }

        /**
         * Get the name of the contig on which the Features in our cache are located
         *
         * @return the name of the contig on which the Features in our cache are located
         */
        public String getContig() {
            return cachedContig;
        }

        /**
         * Get the start position of the interval that all Features in our cache overlap
         *
         * @return the start position of the interval that all Features in our cache overlap
         */
        public long getCacheStart() {
            return cacheStart;
        }

        /**
         * Get the stop position of the interval that all Features in our cache overlap
         *
         * @return the stop position of the interval that all Features in our cache overlap
         */
        public long getCacheStop() {
            return cacheStop;
        }

        /**
         * Does our cache currently contain no Features?
         *
         * @return true if our cache contains no Features, otherwise false
         */
        public boolean isEmpty() {
            return cache.isEmpty();
        }

        /**
         * Clear our cache and fill it with the records from the provided iterator, preserving their
         * relative ordering, and update our contig/start/stop to reflect the new interval that all
         * records in our cache overlap.
         *
         * Typically each fill operation should involve significant lookahead beyond the region
         * requested so that future queries will be cache hits.
         *
         * @param featureIter iterator from which to pull Features with which to populate our cache
         *                    (replacing existing cache contents)
         * @param interval all Features from featureIter overlap this interval
         */
        public void fill( final Iterator<CACHED_FEATURE> featureIter, final GenomeLoc interval ) {
            cache.clear();
            while ( featureIter.hasNext() ) {
                cache.add(featureIter.next());
            }

            cachedContig = interval.getContig();
            cacheStart = interval.getStart();
            cacheStop = interval.getStop();
        }

        /**
         * Determines whether all records overlapping the provided interval are already contained in our cache.
         *
         * @param interval the interval to check against the contents of our cache
         * @return true if all records overlapping the provided interval are already contained in our cache, otherwise false
         */
        public boolean cacheHit( final GenomeLoc interval ) {
            return cachedContig != null &&
                   cachedContig.equals(interval.getContig()) &&
                   cacheStart <= interval.getStart() &&
                   cacheStop >= interval.getStop();
        }

        /**
         * Trims the cache to the specified new start position by discarding all records that end before it
         * while preserving relative ordering of records.
         *
         * @param newStart new start position on the current contig to which to trim the cache
         */
        public void trimToNewStartPosition( final long newStart ) {
            if ( newStart > cacheStop ) {
                throw new GATKException(String.format("BUG: attempted to trim Feature cache to an improper new start position (%d). Cache stop = %d",
                                                      newStart, cacheStop));
            }

            List<CACHED_FEATURE> overlappingFeaturesBeforeNewStart = new ArrayList<>(EXPECTED_MAX_OVERLAPPING_FEATURES_DURING_CACHE_TRIM);

            // In order to trim the cache to the new start position, we need to find
            // all Features in the cache that start before the new start position,
            // and discard those that don't overlap the new start while keeping those
            // that do overlap. We can stop once we find a Feature that starts on or
            // after the new start position, since the Features are assumed to be sorted
            // by start position.
            while ( ! cache.isEmpty() && cache.getFirst().getStart() < newStart ) {
                CACHED_FEATURE featureBeforeNewStart = cache.removeFirst();

                if ( featureBeforeNewStart.getEnd() >= newStart ) {
                    overlappingFeaturesBeforeNewStart.add(featureBeforeNewStart);
                }
            }

            // Add back the Features that started before the new start but overlapped it
            // in the reverse of the order in which we encountered them so that their original
            // relative ordering in the cache is restored.
            for ( int i = overlappingFeaturesBeforeNewStart.size() - 1; i >= 0; --i ) {
                cache.addFirst(overlappingFeaturesBeforeNewStart.get(i));
            }

            // Record our new start boundary
            cacheStart = newStart;
        }

        /**
         * Returns (but does not remove) all cached Features that overlap the region from the start
         * of our cache (cacheStart) to the specified stop position.
         *
         * @param stopPosition Endpoint of the interval that returned Features must overlap
         * @return all cached Features that overlap the region from the start of our cache to the specified stop position
         */
        public List<CACHED_FEATURE> getCachedFeaturesUpToStopPosition( final long stopPosition ) {
            List<CACHED_FEATURE> matchingFeatures = new ArrayList<>(cache.size());

            // Find (but do not remove from our cache) all Features that start before or on the provided stop position
            for ( CACHED_FEATURE candidateFeature : cache ) {
                if ( candidateFeature.getStart() > stopPosition ) {
                    break; // No more possible matches among the remaining cached Features, so stop looking
                }
                matchingFeatures.add(candidateFeature);
            }
            return matchingFeatures;
        }
    }

    /**
     * Creates a FeatureDataSource backed by the provided file that uses the given codec to decode records
     * from that file. The data source will have no name, and will look ahead the default number of bases
     * ({@link #DEFAULT_QUERY_LOOKAHEAD_BASES}) during queries that produce cache misses.
     *
     * @param featureFile file containing Features
     * @param codec codec with which to decode the records from featureFile
     */
    public FeatureDataSource( final File featureFile, final FeatureCodec<T, ?> codec ) {
        this(featureFile, codec, null, DEFAULT_QUERY_LOOKAHEAD_BASES);
    }

    /**
     * Creates a FeatureDataSource backed by the provided File that uses the provided codec to decode records
     * from that file, and assigns this data source a logical name. We will look ahead the default number of bases
     * ({@link #DEFAULT_QUERY_LOOKAHEAD_BASES}) during queries that produce cache misses.
     *
     * @param featureFile file containing Features
     * @param codec codec with which to decode the records from featureFile
     * @param name logical name for this data source (may be null)
     */
    public FeatureDataSource( final File featureFile, final FeatureCodec<T, ?> codec, final String name ) {
        this(featureFile, codec, name, DEFAULT_QUERY_LOOKAHEAD_BASES);
    }

    /**
     * Creates a FeatureDataSource backed by the provided File that uses the provided codec to decode records
     * from that file, and assigns this data source a logical name. We will look ahead the specified number of bases
     * during queries that produce cache misses.
     *
     * @param featureFile file containing Features
     * @param codec codec with which to decode the records from featureFile
     * @param name logical name for this data source (may be null)
     * @param queryLookaheadBases look ahead this many bases during queries that produce cache misses
     */
    public FeatureDataSource( final File featureFile, final FeatureCodec<T, ?> codec, final String name, final int queryLookaheadBases ) {
        if ( featureFile == null || codec == null ) {
            throw new IllegalArgumentException("FeatureDataSource cannot be created from null file/codec");
        }
        if ( queryLookaheadBases < 0 ) {
            throw new IllegalArgumentException("Query lookahead bases must be >= 0");
        }
        if ( ! featureFile.canRead() || featureFile.isDirectory() ) {
            throw new UserException.CouldNotReadInputFile("File " + featureFile.getAbsolutePath() + " does not exist, is unreadable, or is a directory");
        }

        this.featureFile = featureFile;

        try {
            // Instruct the reader factory to not require an index. We will require one ourselves as soon as
            // a query by interval is attempted.
            this.featureReader = AbstractFeatureReader.getFeatureReader(featureFile.getAbsolutePath(), codec, false);
        }
        catch ( TribbleException e ) {
            throw new GATKException("Error initializing feature reader for file " + featureFile.getAbsolutePath(), e);
        }

        this.currentIterator = null;
        this.queryCache = new FeatureCache<>();
        this.queryLookaheadBases = queryLookaheadBases;
        this.codec = codec;
        this.name = name;
        this.hasIndex = featureReader.hasIndex(); // Cache this result, as it's fairly expensive to determine
    }

    /**
     * Gets an iterator over all Features in this data source, unbounded by intervals.
     *
     * Calling this method invalidates (closes) any previous iterator obtained from this method.
     *
     * @return an iterator over all Features in this data source
     */
    @Override
    public Iterator<T> iterator() {
        // Tribble documentation states that having multiple iterators open simultaneously over the same FeatureReader
        // results in undefined behavior
        closeOpenIterationIfNecessary();

        try {
            // Save the iterator returned so that we can close it properly later
            currentIterator = featureReader.iterator();
            return currentIterator;
        }
        catch ( IOException e ) {
            throw new GATKException("Error creating iterator over file " + featureFile.getAbsolutePath(), e);
        }
    }

    /**
     * Gets an iterator over all Features in this data source that overlap the provided interval.
     *
     * Requires the backing file to have been indexed using the IndexFeatureFile tool, and to
     * be sorted in increasing order of start position for each contig.
     *
     * Query results are cached to improve the performance of future queries during typical access
     * patterns. See notes to the class as a whole for a description of the caching strategy.
     *
     * Calling this method potentially invalidates (closes) any other open iterator obtained
     * from this data source via a call to {@link #iterator}
     *
     * @param interval retrieve all Features overlapping this interval
     * @return an iterator over all Features in this data source that overlap the provided interval
     */
    @Override
    public Iterator<T> query( final GenomeLoc interval ) {
        return queryAndPrefetch(interval).iterator();
    }

    /**
     * Returns a List of all Features in this data source that overlap the provided interval.
     *
     * Requires the backing file to have been indexed using the IndexFeatureFile tool, and to
     * be sorted in increasing order of start position for each contig.
     *
     * Query results are cached to improve the performance of future queries during typical access
     * patterns. See notes to the class as a whole for a description of the caching strategy.
     *
     * Calling this method potentially invalidates (closes) any other open iterator obtained
     * from this data source via a call to {@link #iterator}
     *
     * @param interval retrieve all Features overlapping this interval
     * @return a List of all Features in this data source that overlap the provided interval
     */
    public List<T> queryAndPrefetch( final GenomeLoc interval ) {
        if ( ! hasIndex ) {
            throw new UserException("File " + featureFile.getAbsolutePath() + " requires an index to enable queries by interval. " +
                                    "Please index this file using the bundled tool " + IndexFeatureFile.class.getSimpleName());
        }

        // If the query can be satisfied using existing cache contents, prepare for retrieval
        // by discarding all Features at the beginning of the cache that end before the start
        // of our query interval.
        if ( queryCache.cacheHit(interval) ) {
            queryCache.trimToNewStartPosition(interval.getStart());
        }
        // Otherwise, we have a cache miss, so go to disk to refill our cache.
        else {
            refillQueryCache(interval);
        }

        // Return the subset of our cache that overlaps our query interval
        return queryCache.getCachedFeaturesUpToStopPosition(interval.getStop());
    }

    /**
     * Refill our cache from disk after a cache miss. Will prefetch Features overlapping an additional
     * queryLookaheadBases bases after the end of the provided interval, in addition to those overlapping
     * the interval itself.
     *
     * Calling this has the side effect of invalidating (closing) any currently-open iteration over
     * this data source.
     *
     * @param interval the query interval that produced a cache miss
     */
    private void refillQueryCache( final GenomeLoc interval ) {
        // Tribble documentation states that having multiple iterators open simultaneously over the same FeatureReader
        // results in undefined behavior
        closeOpenIterationIfNecessary();

        // Expand the end of our query by the configured number of bases, in anticipation of probable future
        // queries with slightly larger start/stop positions.
        //
        // Note that it doesn't matter if we go off the end of the contig in the process, since
        // our reader's query operation is not aware of (and does not care about) contig boundaries.
        final int queryStop = interval.getStop() + queryLookaheadBases;

        // Query iterator over our reader will be immediately closed after re-populating our cache
        try ( CloseableTribbleIterator<T> queryIter = featureReader.query(interval.getContig(), interval.getStart(), queryStop) ) {
            queryCache.fill(queryIter, interval);
        }
        catch ( IOException e ) {
            throw new GATKException("Error querying file " + featureFile.getAbsolutePath() + " over interval " + interval, e);
        }
    }

    /**
     * Get the class of the codec being used to decode records from our file
     *
     * @return the class of the codec being used to decode records from our file
     */
    @SuppressWarnings("rawtypes")
    public Class<? extends FeatureCodec> getCodecClass() {
        return codec.getClass();
    }

    /**
     * Get the type of Feature record stored in this data source
     *
     * @return the type of Feature record stored in this data source
     */
    public Class<T> getFeatureType() {
        return codec.getFeatureType();
    }

    /**
     * Get the logical name of this data source. Will be null if the data source was not assigned a name.
     *
     * @return the logical name of this data source (may be null)
     */
    public String getName() {
        return name;
    }

    /**
     * Permanently close this data source, invalidating any open iteration over it, and making it invalid for future
     * iterations and queries.
     */
    @Override
    public void close() {
        closeOpenIterationIfNecessary();

        try {
            if ( featureReader != null )
                featureReader.close();
        }
        catch ( IOException e ) {
            throw new GATKException("Error closing Feature reader for file " + featureFile.getAbsolutePath());
        }
    }

    /**
     * Close the iterator currently open over this data source, if there is one.
     */
    private void closeOpenIterationIfNecessary() {
        if ( currentIterator != null ) {
            currentIterator.close();
            currentIterator = null;
        }
    }
}
