/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.provenance.lucene;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexManager implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(IndexManager.class);

    private final Lock lock = new ReentrantLock();
    private final Map<File, IndexWriterCount> writerCounts = new HashMap<>();
    private final Map<File, List<ActiveIndexSearcher>> activeSearchers = new HashMap<>();


    public void removeIndex(final File indexDirectory) {
        final File absoluteFile = indexDirectory.getAbsoluteFile();
        logger.info("Removing index {}", indexDirectory);

        lock.lock();
        try {
            final IndexWriterCount count = writerCounts.remove(absoluteFile);
            if ( count != null ) {
                try {
                    count.close();
                } catch (final IOException ioe) {
                    logger.warn("Failed to close Index Writer {} for {}", count.getWriter(), absoluteFile);
                    if ( logger.isDebugEnabled() ) {
                        logger.warn("", ioe);
                    }
                }
            }

            for ( final List<ActiveIndexSearcher> searcherList : activeSearchers.values() ) {
                for ( final ActiveIndexSearcher searcher : searcherList ) {
                    try {
                        searcher.close();
                    } catch (final IOException ioe) {
                        logger.warn("Failed to close Index Searcher {} for {} due to {}",
                                searcher.getSearcher(), absoluteFile, ioe);
                        if ( logger.isDebugEnabled() ) {
                            logger.warn("", ioe);
                        }
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public IndexWriter borrowIndexWriter(final File indexingDirectory) throws IOException {
        final File absoluteFile = indexingDirectory.getAbsoluteFile();
        logger.debug("Borrowing index writer for {}", indexingDirectory);

        lock.lock();
        try {
            IndexWriterCount writerCount = writerCounts.remove(absoluteFile);
            if ( writerCount == null ) {
                final List<Closeable> closeables = new ArrayList<>();
                final Directory directory = FSDirectory.open(indexingDirectory);
                closeables.add(directory);

                try {
                    final Analyzer analyzer = new StandardAnalyzer();
                    closeables.add(analyzer);

                    final IndexWriterConfig config = new IndexWriterConfig(LuceneUtil.LUCENE_VERSION, analyzer);
                    config.setWriteLockTimeout(300000L);

                    final IndexWriter indexWriter = new IndexWriter(directory, config);
                    writerCount = new IndexWriterCount(indexWriter, analyzer, directory, 1);
                    logger.debug("Providing new index writer for {}", indexingDirectory);
                } catch (final IOException ioe) {
                    for ( final Closeable closeable : closeables ) {
                        try {
                            closeable.close();
                        } catch (final IOException ioe2) {
                            ioe.addSuppressed(ioe2);
                        }
                    }

                    throw ioe;
                }

                writerCounts.put(absoluteFile, writerCount);
            } else {
                logger.debug("Providing existing index writer for {} and incrementing count to {}", indexingDirectory, writerCount.getCount() + 1);
                writerCounts.put(absoluteFile, new IndexWriterCount(writerCount.getWriter(),
                        writerCount.getAnalyzer(), writerCount.getDirectory(), writerCount.getCount() + 1));
            }

            return writerCount.getWriter();
        } finally {
            lock.unlock();
        }
    }

    public void returnIndexWriter(final File indexingDirectory, final IndexWriter writer) {
        final File absoluteFile = indexingDirectory.getAbsoluteFile();
        logger.debug("Returning Index Writer for {} to IndexManager", indexingDirectory);

        lock.lock();
        try {
            IndexWriterCount count = writerCounts.remove(absoluteFile);

            try {
                if ( count == null ) {
                    logger.warn("Index Writer {} was returned to IndexManager for {}, but this writer is not known. "
                            + "This could potentially lead to a resource leak", writer, indexingDirectory);
                    writer.close();
                } else if ( count.getCount() <= 1 ) {
                    // we are finished with this writer.
                    logger.debug("Closing Index Writer for {}", indexingDirectory);
                    count.close();
                } else {
                    // decrement the count.
                    logger.debug("Decrementing count for Index Writer for {} to {}", indexingDirectory, count.getCount() - 1);
                    writerCounts.put(absoluteFile, new IndexWriterCount(count.getWriter(), count.getAnalyzer(), count.getDirectory(), count.getCount() - 1));
                }
            } catch (final IOException ioe) {
                logger.warn("Failed to close Index Writer {} due to {}", writer, ioe);
                if ( logger.isDebugEnabled() ) {
                    logger.warn("", ioe);
                }
            }
        } finally {
            lock.unlock();
        }
    }


    public IndexSearcher borrowIndexSearcher(final File indexDir) throws IOException {
        final File absoluteFile = indexDir.getAbsoluteFile();
        logger.debug("Borrowing index searcher for {}", indexDir);

        lock.lock();
        try {
            // check if we already have a reader cached.
            List<ActiveIndexSearcher> currentlyCached = activeSearchers.get(absoluteFile);
            if ( currentlyCached == null ) {
                currentlyCached = new ArrayList<>();
                activeSearchers.put(absoluteFile, currentlyCached);
            } else {
                // keep track of any searchers that have been closed so that we can remove them
                // from our cache later.
                final Set<ActiveIndexSearcher> expired = new HashSet<>();

                try {
                    for ( final ActiveIndexSearcher searcher : currentlyCached ) {
                        if ( searcher.isCache() ) {
                            final int refCount = searcher.getSearcher().getIndexReader().getRefCount();
                            if ( refCount <= 0 ) {
                                // if refCount == 0, then the reader has been closed, so we need to discard the searcher
                                logger.debug("Reference count for cached Index Searcher for {} is currently {}; "
                                        + "removing cached searcher", absoluteFile, refCount);
                                expired.add(searcher);
                                continue;
                            }

                            logger.debug("Providing previously cached index searcher for {}", indexDir);
                            return searcher.getSearcher();
                        }
                    }
                } finally {
                    // if we have any expired index searchers, we need to close them and remove them
                    // from the cache so that we don't try to use them again later.
                    for ( final ActiveIndexSearcher searcher : expired ) {
                        try {
                            searcher.close();
                        } catch (final Exception e) {
                            logger.debug("Failed to close 'expired' IndexSearcher {}", searcher);
                        }

                        currentlyCached.remove(searcher);
                    }
                }
            }

            IndexWriterCount writerCount = writerCounts.remove(absoluteFile);
            if ( writerCount == null ) {
                final Directory directory = FSDirectory.open(absoluteFile);
                logger.debug("No Index Writer currently exists for {}; creating a cachable reader", indexDir);

                try {
                    final DirectoryReader directoryReader = DirectoryReader.open(directory);
                    final IndexSearcher searcher = new IndexSearcher(directoryReader);

                    // we want to cache the searcher that we create, since it's just a reader.
                    final ActiveIndexSearcher cached = new ActiveIndexSearcher(searcher, directoryReader, directory, true);
                    currentlyCached.add(cached);

                    return cached.getSearcher();
                } catch (final IOException e) {
                    try {
                        directory.close();
                    } catch (final IOException ioe) {
                        e.addSuppressed(ioe);
                    }

                    throw e;
                }
            } else {
                logger.debug("Index Writer currently exists for {}; creating a non-cachable reader and incrementing "
                        + "counter to {}", indexDir, writerCount.getCount() + 1);

                // increment the writer count to ensure that it's kept open.
                writerCounts.put(absoluteFile, new IndexWriterCount(writerCount.getWriter(),
                        writerCount.getAnalyzer(), writerCount.getDirectory(), writerCount.getCount() + 1));

                // create a new Index Searcher from the writer so that we don't have an issue with trying
                // to read from a directory that's locked. If we get the "no segments* file found" with
                // Lucene, this indicates that an IndexWriter already has the directory open.
                final IndexWriter writer = writerCount.getWriter();
                final DirectoryReader directoryReader = DirectoryReader.open(writer, false);
                final IndexSearcher searcher = new IndexSearcher(directoryReader);

                // we don't want to cache this searcher because it's based on a writer, so we want to get
                // new values the next time that we search.
                final ActiveIndexSearcher activeSearcher = new ActiveIndexSearcher(searcher, directoryReader, null, false);

                currentlyCached.add(activeSearcher);
                return activeSearcher.getSearcher();
            }
        } finally {
            lock.unlock();
        }
    }


    public void returnIndexSearcher(final File indexDirectory, final IndexSearcher searcher) {
        final File absoluteFile = indexDirectory.getAbsoluteFile();
        logger.debug("Returning index searcher for {} to IndexManager", indexDirectory);

        lock.lock();
        try {
            // check if we already have a reader cached.
            List<ActiveIndexSearcher> currentlyCached = activeSearchers.get(absoluteFile);
            if ( currentlyCached == null ) {
                logger.warn("Received Index Searcher for {} but no searcher was provided for that directory; this could "
                        + "result in a resource leak", indexDirectory);
                return;
            }

            final Iterator<ActiveIndexSearcher> itr = currentlyCached.iterator();
            while (itr.hasNext()) {
                final ActiveIndexSearcher activeSearcher = itr.next();
                if ( activeSearcher.getSearcher().equals(searcher) ) {
                    if ( activeSearcher.isCache() ) {
                        // the searcher is cached. Just leave it open.
                        logger.debug("Index searcher for {} is cached; leaving open", indexDirectory);
                        return;
                    } else {
                        // searcher is not cached. It was created from a writer, and we want
                        // the newest updates the next time that we get a searcher, so we will
                        // go ahead and close this one out.
                        itr.remove();

                        // decrement the writer count because we incremented it when creating the searcher
                        final IndexWriterCount writerCount = writerCounts.remove(absoluteFile);
                        if ( writerCount != null ) {
                            if ( writerCount.getCount() <= 1 ) {
                                try {
                                    logger.debug("Index searcher for {} is not cached. Writer count is "
                                            + "decremented to {}; closing writer", indexDirectory, writerCount.getCount() - 1);

                                    writerCount.close();
                                } catch (final IOException ioe) {
                                    logger.warn("Failed to close Index Writer for {} due to {}", absoluteFile, ioe);
                                    if ( logger.isDebugEnabled() ) {
                                        logger.warn("", ioe);
                                    }
                                }
                            } else {
                                logger.debug("Index searcher for {} is not cached. Writer count is decremented "
                                        + "to {}; leaving writer open", indexDirectory, writerCount.getCount() - 1);

                                writerCounts.put(absoluteFile, new IndexWriterCount(writerCount.getWriter(),
                                        writerCount.getAnalyzer(), writerCount.getDirectory(),
                                        writerCount.getCount() - 1));
                            }
                        }

                        try {
                            logger.debug("Closing Index Searcher for {}", indexDirectory);
                            activeSearcher.close();
                        } catch (final IOException ioe) {
                            logger.warn("Failed to close Index Searcher for {} due to {}", absoluteFile, ioe);
                            if ( logger.isDebugEnabled() ) {
                                logger.warn("", ioe);
                            }
                        }
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        logger.debug("Closing Index Manager");

        lock.lock();
        try {
            IOException ioe = null;

            for ( final IndexWriterCount count : writerCounts.values() ) {
                try {
                    count.close();
                } catch (final IOException e) {
                    if ( ioe == null ) {
                        ioe = e;
                    } else {
                        ioe.addSuppressed(e);
                    }
                }
            }

            for (final List<ActiveIndexSearcher> searcherList : activeSearchers.values()) {
                for (final ActiveIndexSearcher searcher : searcherList) {
                    try {
                        searcher.close();
                    } catch (final IOException e) {
                        if ( ioe == null ) {
                            ioe = e;
                        } else {
                            ioe.addSuppressed(e);
                        }
                    }
                }
            }

            if ( ioe != null ) {
                throw ioe;
            }
        } finally {
            lock.unlock();
        }
    }


    private static void close(final Closeable... closeables) throws IOException {
        IOException ioe = null;
        for ( final Closeable closeable : closeables ) {
            if ( closeable == null ) {
                continue;
            }

            try {
                closeable.close();
            } catch (final IOException e) {
                if ( ioe == null ) {
                    ioe = e;
                } else {
                    ioe.addSuppressed(e);
                }
            }
        }

        if ( ioe != null ) {
            throw ioe;
        }
    }


    private static class ActiveIndexSearcher implements Closeable {
        private final IndexSearcher searcher;
        private final DirectoryReader directoryReader;
        private final Directory directory;
        private final boolean cache;

        public ActiveIndexSearcher(IndexSearcher searcher, DirectoryReader directoryReader,
                Directory directory, final boolean cache) {
            this.searcher = searcher;
            this.directoryReader = directoryReader;
            this.directory = directory;
            this.cache = cache;
        }

        public boolean isCache() {
            return cache;
        }

        public IndexSearcher getSearcher() {
            return searcher;
        }

        @Override
        public void close() throws IOException {
            IndexManager.close(directoryReader, directory);
        }
    }


    private static class IndexWriterCount implements Closeable {
        private final IndexWriter writer;
        private final Analyzer analyzer;
        private final Directory directory;
        private final int count;

        public IndexWriterCount(final IndexWriter writer, final Analyzer analyzer, final Directory directory, final int count) {
            this.writer = writer;
            this.analyzer = analyzer;
            this.directory = directory;
            this.count = count;
        }

        public Analyzer getAnalyzer() {
            return analyzer;
        }

        public Directory getDirectory() {
            return directory;
        }

        public IndexWriter getWriter() {
            return writer;
        }

        public int getCount() {
            return count;
        }

        @Override
        public void close() throws IOException {
            IndexManager.close(writer, analyzer, directory);
        }
    }

}
