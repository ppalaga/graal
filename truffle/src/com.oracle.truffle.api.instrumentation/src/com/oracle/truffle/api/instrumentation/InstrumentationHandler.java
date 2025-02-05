/*
 * Copyright (c) 2016, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.instrumentation;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.ref.WeakReference;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import org.graalvm.options.OptionValues;
import org.graalvm.polyglot.io.MessageTransport;

import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.impl.Accessor;
import com.oracle.truffle.api.impl.DispatchOutputStream;
import com.oracle.truffle.api.instrumentation.InstrumentableNode.WrapperNode;
import com.oracle.truffle.api.instrumentation.ProbeNode.EventChainNode;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Env;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.NodeVisitor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Central coordinator class for the Truffle instrumentation framework. Allocated once per
 * {@linkplain org.graalvm.polyglot.Engine engine}.
 */
final class InstrumentationHandler {

    /* Enable trace output to stdout. */
    static final boolean TRACE = Boolean.getBoolean("truffle.instrumentation.trace");

    private final Object polyglotEngine;

    /*
     * The contract is the following: "sources" and "sourcesList" can only be accessed while
     * synchronized on "sources". both will only be lazily initialized from "loadedRoots" when the
     * first sourceBindings is added, by calling lazyInitializeSourcesList(). "sourcesList" will be
     * null as long as the sources haven't been initialized.
     */
    private final Map<Source, Void> sources = Collections.synchronizedMap(new WeakHashMap<Source, Void>());
    /* Load order needs to be preserved for sources, thats why we store sources again in a list. */
    private final AtomicReference<SourceList> sourcesListRef = new AtomicReference<>();
    private volatile boolean hasSourceBindings;
    private volatile boolean collectingSources;
    /*
     * The contract is the following: "sourcesExecuted" and "sourcesExecutedList" can only be
     * accessed while synchronized on "sourcesExecuted". Both will only be lazily initialized from
     * "onFirstExecution" when the first sourceExecutedBindings is added, by calling
     * lazyInitializeSourcesExecutedList(). "sourcesExecutedList" will be null as long as the
     * sources haven't been executed.
     */
    private final Map<Source, Void> sourcesExecuted = Collections.synchronizedMap(new WeakHashMap<Source, Void>());
    /* Load order needs to be preserved for sources, thats why we store sources again in a list. */
    private final AtomicReference<SourceList> sourcesExecutedListRef = new AtomicReference<>();
    private volatile boolean hasSourceExecutedBindings;
    private volatile boolean collectingSourcesExecuted;

    final Collection<RootNode> loadedRoots = new WeakAsyncList<>(256);
    private final Collection<RootNode> executedRoots = new WeakAsyncList<>(64);
    private final Collection<AllocationReporter> allocationReporters = new WeakAsyncList<>(16);

    private final Collection<EventBinding.Source<?>> executionBindings = new EventBindingList<>(8);
    private final Collection<EventBinding.Source<?>> sourceSectionBindings = new EventBindingList<>(8);
    private final Collection<EventBinding.Source<?>> sourceBindings = new EventBindingList<>(8);
    private final ThreadLocal<FindSourcesVisitor> findSourcesVisitor = new ThreadLocalSourcesVisitor();
    private final Collection<EventBinding.Source<?>> sourceExecutedBindings = new EventBindingList<>(8);
    private final ThreadLocal<FindSourcesVisitor> findSourcesExecutedVisitor = new ThreadLocalExecutedSourcesVisitor();
    private final Collection<EventBinding<? extends OutputStream>> outputStdBindings = new EventBindingList<>(1);
    private final Collection<EventBinding<? extends OutputStream>> outputErrBindings = new EventBindingList<>(1);
    private final Collection<EventBinding.Allocation<? extends AllocationListener>> allocationBindings = new EventBindingList<>(2);
    private final Collection<EventBinding<? extends ContextsListener>> contextsBindings = new EventBindingList<>(8);
    private final Collection<EventBinding<? extends ThreadsListener>> threadsBindings = new EventBindingList<>(8);
    private final ReadWriteLock sourceBindingsLock = new ReentrantReadWriteLock();
    private final ReadWriteLock sourceExecutedBindingsLock = new ReentrantReadWriteLock();

    /*
     * Fast lookup of instrumenter instances based on a key provided by the accessor.
     */
    final ConcurrentHashMap<Object, AbstractInstrumenter> instrumenterMap = new ConcurrentHashMap<>();

    private DispatchOutputStream out;   // effectively final
    private DispatchOutputStream err;   // effectively final
    private InputStream in;             // effectively final
    private MessageTransport messageInterceptor; // effectively final
    private final Map<Class<?>, Set<Class<?>>> cachedProvidedTags = new ConcurrentHashMap<>();

    final EngineInstrumenter engineInstrumenter;

    InstrumentationHandler(Object polyglotEngine, DispatchOutputStream out, DispatchOutputStream err, InputStream in, MessageTransport messageInterceptor) {
        this.polyglotEngine = polyglotEngine;
        this.out = out;
        this.err = err;
        this.in = in;
        this.messageInterceptor = messageInterceptor;
        this.engineInstrumenter = new EngineInstrumenter();
    }

    Object getSourceVM() {
        return polyglotEngine;
    }

    void onLoad(RootNode root) {
        if (!InstrumentAccessor.nodesAccess().isInstrumentable(root)) {
            return;
        }
        assert root.getLanguageInfo() != null;
        if (hasSourceBindings) {
            final Source[] rootSources;
            Lock lock = sourceBindingsLock.readLock();
            lock.lock();
            try {
                if (!sourceBindings.isEmpty() || collectingSources) {
                    // we'll add to the sourcesList, so it needs to be initialized
                    lazyInitializeSourcesList();

                    FindSourcesVisitor visitor = findSourcesVisitor.get();
                    SourceSection sourceSection = root.getSourceSection();
                    if (sourceSection != null) {
                        visitor.adoptSource(sourceSection.getSource());
                    }
                    RootNode previousRoot = visitor.root;
                    Set<Class<?>> previousProvidedTags = visitor.providedTags;
                    SourceSection previousRootSourceSection = visitor.rootSourceSection;
                    int previousRootBits = visitor.rootBits;
                    visitRoot(root, root, visitor, false, true);
                    rootSources = visitor.getSources();
                    visitor.root = previousRoot;
                    visitor.providedTags = previousProvidedTags;
                    visitor.rootSourceSection = previousRootSourceSection;
                    visitor.rootBits = previousRootBits;
                } else {
                    hasSourceBindings = false;
                    sources.clear();
                    sourcesListRef.set(null);
                    rootSources = null;
                }
                loadedRoots.add(root);
                // Do not invoke foreign code while holding a lock to avoid deadlocks.
                if (rootSources != null) {
                    SourceList sourceList = sourcesListRef.get();
                    if (sourceList == null || !sourceList.addIfIncomplete(rootSources)) {
                        for (Source src : rootSources) {
                            notifySourceBindingsLoaded(sourceBindings, src);
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        } else {
            loadedRoots.add(root);
        }

        // fast path no bindings attached
        if (!sourceSectionBindings.isEmpty()) {
            BindingsVisitorBuilder visitorBuilder = new BindingsVisitorBuilder();
            visitorBuilder.addNotifyLoadedOperationForAllBindings(VisitOperation.Scope.ALL);
            visitRoot(root, root, visitorBuilder.buildVisitor(), false, true);
        }

    }

    private static class FindSourcesVisitor extends AbstractNodeVisitor {

        private final Map<Source, Void> sources;
        private final AtomicReference<SourceList> sourcesListRef;
        private final List<Source> rootSources = new ArrayList<>(5);

        FindSourcesVisitor(Map<Source, Void> sources, AtomicReference<SourceList> sourcesListRef) {
            super(true);
            this.sources = sources;
            this.sourcesListRef = sourcesListRef;
        }

        @Override
        boolean shouldVisit() {
            return true;
        }

        @Override
        protected void visitInstrumentable(Node parentInstrumentable, SourceSection parentSourceSection, Node instrumentableNode, SourceSection sourceSection) {
            if (sourceSection != null) {
                adoptSource(sourceSection.getSource());
            }
        }

        void adoptSource(Source source) {
            synchronized (sources) {
                if (!sources.containsKey(source)) {
                    sources.put(source, null);
                    sourcesListRef.get().add(source);
                    rootSources.add(source);
                }
            }
        }

        Source[] getSources() {
            if (rootSources.isEmpty()) {
                return null;
            }
            Source[] sourcesArray = rootSources.toArray(new Source[rootSources.size()]);
            rootSources.clear();
            return sourcesArray;
        }

    }

    private class ThreadLocalSourcesVisitor extends ThreadLocal<FindSourcesVisitor> {

        @Override
        protected FindSourcesVisitor initialValue() {
            return new FindSourcesVisitor(sources, sourcesListRef);
        }

    }

    private class ThreadLocalExecutedSourcesVisitor extends ThreadLocal<FindSourcesVisitor> {

        @Override
        protected FindSourcesVisitor initialValue() {
            return new FindSourcesVisitor(sourcesExecuted, sourcesExecutedListRef);
        }

    }

    void onFirstExecution(RootNode root) {
        if (!InstrumentAccessor.nodesAccess().isInstrumentable(root)) {
            return;
        }
        assert root.getLanguageInfo() != null;
        if (hasSourceExecutedBindings) {
            final Source[] rootSources;
            Lock lock = sourceExecutedBindingsLock.readLock();
            lock.lock();
            try {
                if (!sourceExecutedBindings.isEmpty() || collectingSourcesExecuted) {
                    // we'll add to the sourcesExecutedList, so it needs to be initialized
                    lazyInitializeSourcesExecutedList();

                    int rootBits = RootNodeBits.get(root);
                    if (RootNodeBits.isNoSourceSection(rootBits)) {
                        rootSources = null;
                    } else {
                        FindSourcesVisitor visitor = findSourcesExecutedVisitor.get();
                        SourceSection sourceSection = root.getSourceSection();
                        if (RootNodeBits.isSameSource(rootBits) && sourceSection != null) {
                            Source source = sourceSection.getSource();
                            visitor.adoptSource(source);
                        } else {
                            if (sourceSection != null) {
                                visitor.adoptSource(sourceSection.getSource());
                            }
                            RootNode previousRoot = visitor.root;
                            Set<Class<?>> previousProvidedTags = visitor.providedTags;
                            SourceSection previousRootSourceSection = visitor.rootSourceSection;
                            int previousRootBits = visitor.rootBits;
                            visitRoot(root, root, visitor, false, true);
                            visitor.root = previousRoot;
                            visitor.providedTags = previousProvidedTags;
                            visitor.rootSourceSection = previousRootSourceSection;
                            visitor.rootBits = previousRootBits;
                        }
                        rootSources = visitor.getSources();
                    }
                } else {
                    hasSourceExecutedBindings = false;
                    sourcesExecuted.clear();
                    sourcesExecutedListRef.set(null);
                    rootSources = null;
                }
                executedRoots.add(root);
                // Do not invoke foreign code while holding a lock to avoid deadlocks.
                if (rootSources != null) {
                    SourceList sourceList = sourcesExecutedListRef.get();
                    if (sourceList == null || !sourceList.addIfIncomplete(rootSources)) {
                        for (Source src : rootSources) {
                            notifySourceExecutedBindings(sourceExecutedBindings, src);
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        } else {
            executedRoots.add(root);
        }

        // fast path no bindings attached
        if (!executionBindings.isEmpty()) {
            BindingsVisitorBuilder visitorBuilder = new BindingsVisitorBuilder();
            visitorBuilder.addInsertWrapperOperationForAllBindings(VisitOperation.Scope.ALL);
            visitRoot(root, root, visitorBuilder.buildVisitor(), false, true);
        }
    }

    void initializeInstrument(Object polyglotInstrument, String instrumentClassName, Supplier<? extends Object> instrumentSupplier) {
        if (TRACE) {
            trace("Initialize instrument class %s %n", instrumentClassName);
        }

        Env env = new Env(polyglotInstrument, out, err, in, messageInterceptor);
        try {
            TruffleInstrument instrument = (TruffleInstrument) instrumentSupplier.get();
            env.instrumenter = new InstrumentClientInstrumenter(env, instrumentClassName);
            env.instrumenter.instrument = instrument;
        } catch (Exception e) {
            failInstrumentInitialization(env, String.format("Failed to create new instrumenter class %s", instrumentClassName), e);
            return;
        }

        if (TRACE) {
            trace("Initialized instrument %s class %s %n", env.instrumenter.instrument, instrumentClassName);
        }

        addInstrumenter(polyglotInstrument, env.instrumenter);
    }

    void createInstrument(Object vmObject, String[] expectedServices, OptionValues optionValues) {
        InstrumentClientInstrumenter instrumenter = ((InstrumentClientInstrumenter) instrumenterMap.get(vmObject));
        instrumenter.env.options = optionValues;
        instrumenter.create(expectedServices);
    }

    void finalizeInstrumenter(Object key) {
        AbstractInstrumenter finalisingInstrumenter = instrumenterMap.get(key);
        if (finalisingInstrumenter == null) {
            throw new AssertionError("Instrumenter already disposed.");
        }
        finalisingInstrumenter.doFinalize();
    }

    void disposeInstrumenter(Object key, boolean cleanupRequired) {
        AbstractInstrumenter disposedInstrumenter = instrumenterMap.remove(key);
        if (disposedInstrumenter == null) {
            throw new AssertionError("Instrumenter already disposed.");
        }
        if (TRACE) {
            trace("BEGIN: Dispose instrumenter %n", key);
        }
        disposedInstrumenter.dispose();

        if (cleanupRequired) {
            Collection<EventBinding.Source<?>> disposedExecutionBindings = filterBindingsForInstrumenter(executionBindings, disposedInstrumenter);
            if (!disposedExecutionBindings.isEmpty()) {
                BindingsVisitorBuilder visitorBuilder = new BindingsVisitorBuilder();
                visitorBuilder.addDisposeWrapperOperationForBindings(disposedExecutionBindings);
                visitRoots(executedRoots, visitorBuilder.buildVisitor());
            }
            disposeBindingsBulk(disposedExecutionBindings);
            disposeBindingsBulk(filterBindingsForInstrumenter(sourceSectionBindings, disposedInstrumenter));
            disposeBindingsBulk(filterBindingsForInstrumenter(sourceBindings, disposedInstrumenter));
            disposeOutputBindingsBulk(out, outputStdBindings);
            disposeOutputBindingsBulk(err, outputErrBindings);
        }
        if (TRACE) {
            trace("END: Disposed instrumenter %n", key);
        }
    }

    private static void disposeBindingsBulk(Collection<EventBinding.Source<?>> list) {
        for (EventBinding<?> binding : list) {
            binding.disposeBulk();
        }
    }

    private static void disposeOutputBindingsBulk(DispatchOutputStream dos, Collection<EventBinding<? extends OutputStream>> list) {
        for (EventBinding<? extends OutputStream> binding : list) {
            InstrumentAccessor.engineAccess().detachOutputConsumer(dos, binding.getElement());
            binding.disposeBulk();
        }
    }

    Instrumenter forLanguage(TruffleLanguage<?> language) {
        return new LanguageClientInstrumenter<>(language);
    }

    <T> EventBinding<T> addExecutionBinding(EventBinding.Source<T> binding) {
        if (TRACE) {
            trace("BEGIN: Adding execution binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        this.executionBindings.add(binding);

        if (!executedRoots.isEmpty()) {
            BindingsVisitorBuilder visitorBuilder = new BindingsVisitorBuilder();
            visitorBuilder.addInsertWrapperOperationForBinding(VisitOperation.Scope.ONLY_ORIGINAL, binding);
            visitorBuilder.addInsertWrapperOperationForAllBindings(VisitOperation.Scope.ONLY_MATERIALIZED);
            visitorBuilder.addNotifyLoadedOperationForAllBindings(VisitOperation.Scope.ONLY_MATERIALIZED);
            visitRoots(executedRoots, visitorBuilder.buildVisitor());
        }

        if (TRACE) {
            trace("END: Added execution binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        return binding;
    }

    <T> EventBinding<T> addSourceSectionBinding(EventBinding.Source<T> binding, boolean notifyLoaded) {
        if (TRACE) {
            trace("BEGIN: Adding binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        this.sourceSectionBindings.add(binding);
        if (notifyLoaded) {
            if (!loadedRoots.isEmpty()) {
                BindingsVisitorBuilder visitorBuilder = new BindingsVisitorBuilder();
                visitorBuilder.addNotifyLoadedOperationForBinding(VisitOperation.Scope.ONLY_ORIGINAL, binding);
                visitorBuilder.addNotifyLoadedOperationForAllBindings(VisitOperation.Scope.ONLY_MATERIALIZED);
                visitorBuilder.addInsertWrapperOperationForAllBindings(VisitOperation.Scope.ONLY_MATERIALIZED);
                visitRoots(loadedRoots, visitorBuilder.buildVisitor());
            }
        }

        if (TRACE) {
            trace("END: Added binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        return binding;
    }

    private void visitLoadedSourceSections(EventBinding.Source<?> binding) {
        if (TRACE) {
            trace("BEGIN: Visiting loaded source sections %s, %s%n", binding.getFilter(), binding.getElement());
        }

        if (!loadedRoots.isEmpty()) {
            BindingsVisitorBuilder visitorBuilder = new BindingsVisitorBuilder();
            visitorBuilder.addNotifyLoadedOperationForBinding(VisitOperation.Scope.ALL, binding);
            visitorBuilder.addNotifyLoadedOperationForAllBindings(VisitOperation.Scope.ONLY_MATERIALIZED);
            visitorBuilder.addInsertWrapperOperationForAllBindings(VisitOperation.Scope.ONLY_MATERIALIZED);
            visitRoots(loadedRoots, visitorBuilder.buildVisitor());
        }

        if (TRACE) {
            trace("END: Visited loaded source sections %s, %s%n", binding.getFilter(), binding.getElement());
        }
    }

    <T> EventBinding<T> addSourceBinding(EventBinding.Source<T> binding, boolean notifyLoaded) {
        if (TRACE) {
            trace("BEGIN: Adding source binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        if (notifyLoaded) {
            this.collectingSources = true;
            this.hasSourceBindings = true;
            lazyInitializeSourcesList();
        }
        Lock lock = sourceBindingsLock.writeLock();
        lock.lock();
        try {
            this.sourceBindings.add(binding);
            this.hasSourceBindings = true;
            if (notifyLoaded) {
                this.collectingSources = false;
                for (Source source : sourcesListRef.get().getCompleteList()) {
                    notifySourceBindingLoaded(binding, source);
                }
            }
        } finally {
            lock.unlock();
        }

        if (TRACE) {
            trace("END: Added source binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        return binding;
    }

    <T> EventBinding<T> addSourceExecutionBinding(EventBinding.Source<T> binding, boolean notifyLoaded) {
        if (TRACE) {
            trace("BEGIN: Adding source execution binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        if (notifyLoaded) {
            this.collectingSourcesExecuted = true;
            this.hasSourceExecutedBindings = true;
            lazyInitializeSourcesExecutedList();
        }
        Lock lock = sourceExecutedBindingsLock.writeLock();
        lock.lock();
        try {
            this.sourceExecutedBindings.add(binding);
            this.hasSourceExecutedBindings = true;
            if (notifyLoaded) {
                this.collectingSourcesExecuted = false;
                for (Source source : sourcesExecutedListRef.get().getCompleteList()) {
                    notifySourceExecutedBinding(binding, source);
                }
            }
        } finally {
            lock.unlock();
        }

        if (TRACE) {
            trace("END: Added source execution binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        return binding;
    }

    <T extends OutputStream> EventBinding<T> addOutputBinding(EventBinding<T> binding, boolean errorOutput) {
        if (TRACE) {
            String kind = (errorOutput) ? "error" : "standard";
            trace("BEGIN: Adding " + kind + " output binding %s%n", binding.getElement());
        }

        if (errorOutput) {
            this.outputErrBindings.add(binding);
            InstrumentAccessor.engineAccess().attachOutputConsumer(this.err, binding.getElement());
        } else {
            this.outputStdBindings.add(binding);
            InstrumentAccessor.engineAccess().attachOutputConsumer(this.out, binding.getElement());
        }

        if (TRACE) {
            String kind = (errorOutput) ? "error" : "standard";
            trace("END: Added " + kind + " output binding %s%n", binding.getElement());
        }

        return binding;
    }

    private <T extends AllocationListener> EventBinding<T> addAllocationBinding(EventBinding.Allocation<T> binding) {
        if (TRACE) {
            trace("BEGIN: Adding allocation binding %s%n", binding.getElement());
        }

        this.allocationBindings.add(binding);
        for (AllocationReporter allocationReporter : allocationReporters) {
            if (binding.getAllocationFilter().contains(allocationReporter.language)) {
                allocationReporter.addListener(binding.getElement());
            }
        }

        if (TRACE) {
            trace("END: Added allocation binding %s%n", binding.getElement());
        }
        return binding;
    }

    private <T extends ContextsListener> EventBinding<T> addContextsBinding(EventBinding<T> binding, boolean includeActiveContexts) {
        if (TRACE) {
            trace("BEGIN: Adding contexts binding %s%n", binding.getElement());
        }

        contextsBindings.add(binding);
        if (includeActiveContexts) {
            Accessor.EngineSupport engineAccess = InstrumentAccessor.engineAccess();
            engineAccess.reportAllLanguageContexts(polyglotEngine, binding.getElement());
        }

        if (TRACE) {
            trace("END: Added contexts binding %s%n", binding.getElement());
        }
        return binding;
    }

    private <T extends ThreadsListener> EventBinding<T> addThreadsBinding(EventBinding<T> binding, boolean includeStartedThreads) {
        if (TRACE) {
            trace("BEGIN: Adding threads binding %s%n", binding.getElement());
        }

        threadsBindings.add(binding);
        if (includeStartedThreads) {
            Accessor.EngineSupport engineAccess = InstrumentAccessor.engineAccess();
            engineAccess.reportAllContextThreads(polyglotEngine, binding.getElement());
        }

        if (TRACE) {
            trace("END: Added threads binding %s%n", binding.getElement());
        }
        return binding;
    }

    /**
     * Initializes sources and sourcesList by populating them from loadedRoots.
     */
    private void lazyInitializeSourcesList() {
        if (sourcesListRef.get() == null) {
            // build the sourcesList, we need it now
            SourceList sourceList = new SourceList();
            if (sourcesListRef.compareAndSet(null, sourceList)) {
                try {
                    for (RootNode root : loadedRoots) {
                        int rootBits = RootNodeBits.get(root);
                        if (RootNodeBits.isNoSourceSection(rootBits)) {
                            continue;
                        } else {
                            SourceSection sourceSection = root.getSourceSection();
                            if (RootNodeBits.isSameSource(rootBits) && sourceSection != null) {
                                Source source = sourceSection.getSource();
                                if (!sources.containsKey(source)) {
                                    sources.put(source, null);
                                    sourceList.add(source);
                                }
                            } else {
                                FindSourcesVisitor visitor = findSourcesVisitor.get();
                                if (sourceSection != null) {
                                    visitor.adoptSource(sourceSection.getSource());
                                }
                                visitRoot(root, root, visitor, false, false);
                                Source[] visitedSources = visitor.getSources();
                                if (visitedSources != null) {
                                    for (Source source : visitedSources) {
                                        if (!sources.containsKey(source)) {
                                            sources.put(source, null);
                                            sourceList.add(source);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    sourceList.notifyComplete();
                }
            }
        }
    }

    /**
     * Initializes sourcesExecuted and sourcesExecutedList by populating them from executedRoots.
     */
    private void lazyInitializeSourcesExecutedList() {
        if (sourcesExecutedListRef.get() == null) {
            // build the sourcesExecutedList, we need it now
            SourceList sourcesExecutedList = new SourceList();
            if (sourcesExecutedListRef.compareAndSet(null, sourcesExecutedList)) {
                try {
                    for (RootNode root : executedRoots) {
                        int rootBits = RootNodeBits.get(root);
                        if (RootNodeBits.isNoSourceSection(rootBits)) {
                            continue;
                        } else {
                            SourceSection sourceSection = root.getSourceSection();
                            if (RootNodeBits.isSameSource(rootBits) && sourceSection != null) {
                                Source source = sourceSection.getSource();
                                if (!sourcesExecuted.containsKey(source)) {
                                    sourcesExecuted.put(source, null);
                                    sourcesExecutedList.add(source);
                                }
                            } else {
                                FindSourcesVisitor visitor = findSourcesExecutedVisitor.get();
                                if (sourceSection != null) {
                                    visitor.adoptSource(sourceSection.getSource());
                                }
                                visitRoot(root, root, visitor, false, false);
                                Source[] visitedSources = visitor.getSources();
                                if (visitedSources != null) {
                                    for (Source source : visitedSources) {
                                        if (!sourcesExecuted.containsKey(source)) {
                                            sourcesExecuted.put(source, null);
                                            sourcesExecutedList.add(source);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    sourcesExecutedList.notifyComplete();
                }
            }
        }
    }

    private void visitRoots(Collection<RootNode> roots, AbstractNodeVisitor addBindingsVisitor) {
        for (RootNode root : roots) {
            visitRoot(root, root, addBindingsVisitor, false, false);
        }
    }

    @SuppressWarnings("deprecation")
    void disposeBinding(EventBinding<?> binding) {
        if (TRACE) {
            trace("BEGIN: Dispose binding %s%n", binding.getElement());
        }

        if (binding instanceof EventBinding.Source) {
            EventBinding.Source<?> sourceBinding = (EventBinding.Source<?>) binding;
            if (sourceBinding.isExecutionEvent()) {
                BindingsVisitorBuilder visitorBuilder = new BindingsVisitorBuilder();
                visitorBuilder.addDisposeWrapperOperationForBinding(sourceBinding);
                visitRoots(executedRoots, visitorBuilder.buildVisitor());
            }
        } else if (binding instanceof EventBinding.Allocation) {
            EventBinding.Allocation<?> allocationBinding = (EventBinding.Allocation<?>) binding;
            AllocationListener l = (AllocationListener) binding.getElement();
            for (AllocationReporter allocationReporter : allocationReporters) {
                if (allocationBinding.getAllocationFilter().contains(allocationReporter.language)) {
                    allocationReporter.removeListener(l);
                }
            }
        } else {
            Object elm = binding.getElement();
            if (elm instanceof OutputStream) {
                if (outputErrBindings.contains(binding)) {
                    InstrumentAccessor.engineAccess().detachOutputConsumer(err, (OutputStream) elm);
                } else if (outputStdBindings.contains(binding)) {
                    InstrumentAccessor.engineAccess().detachOutputConsumer(out, (OutputStream) elm);
                }
            } else if (elm instanceof ContextsListener) {
                // binding disposed
            } else if (elm instanceof ThreadsListener) {
                // binding disposed
            } else {
                assert false : "Unexpected binding " + binding + " with element " + elm;
            }
        }

        if (TRACE) {
            trace("END: Disposed binding %s%n", binding.getElement());
        }
    }

    EventChainNode createBindings(VirtualFrame frame, ProbeNode probeNodeImpl) {
        EventContext context = probeNodeImpl.getContext();
        SourceSection sourceSection = context.getInstrumentedSourceSection();
        if (TRACE) {
            trace("BEGIN: Lazy update for %s%n", sourceSection);
        }

        RootNode rootNode;

        Node parentInstrumentable = null;
        SourceSection parentInstrumentableSourceSection = null;
        Node parentNode = probeNodeImpl.getParent();
        while (parentNode != null && parentNode.getParent() != null) {
            if (parentInstrumentable == null) {
                SourceSection parentSourceSection = parentNode.getSourceSection();
                if (isInstrumentableNode(parentNode)) {
                    parentInstrumentable = parentNode;
                    parentInstrumentableSourceSection = parentSourceSection;
                }
            }
            parentNode = parentNode.getParent();
        }
        if (parentNode instanceof RootNode) {
            rootNode = (RootNode) parentNode;
        } else {
            throw new AssertionError();
        }

        Node instrumentedNode = probeNodeImpl.getContext().getInstrumentedNode();
        Set<Class<?>> providedTags = getProvidedTags(rootNode);
        EventChainNode root = null;
        EventChainNode parent = null;
        for (EventBinding.Source<?> binding : executionBindings) {
            if (binding.isChildInstrumentedFull(providedTags, rootNode, parentInstrumentable, parentInstrumentableSourceSection, instrumentedNode, sourceSection)) {
                if (TRACE) {
                    trace("  Found input value binding %s, %s%n", binding.getInputFilter(), System.identityHashCode(binding));
                }

                EventChainNode next = probeNodeImpl.createParentEventChainCallback(frame, binding, rootNode, providedTags);
                if (next == null) {
                    // inconsistent AST
                    continue;
                }

                if (root == null) {
                    root = next;
                } else {
                    assert parent != null;
                    parent.setNext(next);
                }
                parent = next;
            }

            if (binding.isInstrumentedFull(providedTags, rootNode, instrumentedNode, sourceSection)) {
                if (TRACE) {
                    trace("  Found binding %s, %s%n", binding.getFilter(), binding.getElement());
                }
                EventChainNode next = probeNodeImpl.createEventChainCallback(frame, binding, rootNode, providedTags, instrumentedNode, sourceSection);
                if (next == null) {
                    continue;
                }
                if (root == null) {
                    root = next;
                } else {
                    assert parent != null;
                    parent.setNext(next);
                }
                parent = next;
            }
        }

        if (TRACE) {
            trace("END: Lazy updated for %s%n", sourceSection);
        }
        return root;
    }

    public void onNodeInserted(RootNode rootNode, Node tree) {
        // for input filters to be updated correctly we need to
        // start traversing with the parent instrumentable node.
        Node parentInstrumentable = tree;
        while (parentInstrumentable != null && parentInstrumentable.getParent() != null) {
            parentInstrumentable = parentInstrumentable.getParent();
            if (InstrumentationHandler.isInstrumentableNode(parentInstrumentable)) {
                break;
            }
        }
        assert parentInstrumentable != null;

        if (!sourceSectionBindings.isEmpty() || !executionBindings.isEmpty()) {
            BindingsVisitorBuilder visitorBuilder = new BindingsVisitorBuilder();
            visitorBuilder.addNotifyLoadedOperationForAllBindings(VisitOperation.Scope.ALL);
            visitorBuilder.addInsertWrapperOperationForAllBindings(VisitOperation.Scope.ALL);
            visitRoot(rootNode, parentInstrumentable, visitorBuilder.buildVisitor(), true, false);
        }
    }

    private static void notifySourceBindingsLoaded(Collection<EventBinding.Source<?>> bindings, Source source) {
        for (EventBinding.Source<?> binding : bindings) {
            notifySourceBindingLoaded(binding, source);
        }
    }

    private static void notifySourceBindingLoaded(EventBinding.Source<?> binding, Source source) {
        if (!binding.isDisposed() && binding.isInstrumentedSource(source)) {
            try {
                ((LoadSourceListener) binding.getElement()).onLoad(new LoadSourceEvent(source));
            } catch (Throwable t) {
                if (binding.isLanguageBinding()) {
                    throw t;
                } else {
                    ProbeNode.exceptionEventForClientInstrument(binding, "onLoad", t);
                }
            }
        }
    }

    private static void notifySourceExecutedBindings(Collection<EventBinding.Source<?>> bindings, Source source) {
        for (EventBinding.Source<?> binding : bindings) {
            notifySourceExecutedBinding(binding, source);
        }
    }

    private static void notifySourceExecutedBinding(EventBinding.Source<?> binding, Source source) {
        if (!binding.isDisposed() && binding.isInstrumentedSource(source)) {
            try {
                ((ExecuteSourceListener) binding.getElement()).onExecute(new ExecuteSourceEvent(source));
            } catch (Throwable t) {
                if (binding.isLanguageBinding()) {
                    throw t;
                } else {
                    ProbeNode.exceptionEventForClientInstrument(binding, "onExecute", t);
                }
            }
        }
    }

    static void notifySourceSectionLoaded(EventBinding.Source<?> binding, Node node, SourceSection section) {
        if (section == null) {
            // Do not report null source sections to keep compatibility with the past behavior.
            return;
        }
        LoadSourceSectionListener listener = (LoadSourceSectionListener) binding.getElement();
        try {
            listener.onLoad(new LoadSourceSectionEvent(section, node));
        } catch (Throwable t) {
            if (binding.isLanguageBinding()) {
                throw t;
            } else {
                ProbeNode.exceptionEventForClientInstrument(binding, "onLoad", t);
            }
        }
    }

    private void addInstrumenter(Object key, AbstractInstrumenter instrumenter) throws AssertionError {
        Object previousKey = instrumenterMap.putIfAbsent(key, instrumenter);
        if (previousKey != null) {
            throw new AssertionError("Instrumenter already present.");
        }
    }

    private static Collection<EventBinding.Source<?>> filterBindingsForInstrumenter(Collection<EventBinding.Source<?>> bindings, AbstractInstrumenter instrumenter) {
        if (bindings.isEmpty()) {
            return Collections.emptyList();
        }
        Collection<EventBinding.Source<?>> newBindings = new ArrayList<>();
        for (EventBinding.Source<?> binding : bindings) {
            if (binding.getInstrumenter() == instrumenter) {
                newBindings.add(binding);
            }
        }
        return newBindings;
    }

    private void insertWrapper(Node instrumentableNode, SourceSection sourceSection) {
        Lock lock = InstrumentAccessor.nodesAccess().getLock(instrumentableNode);
        try {
            lock.lock();
            insertWrapperImpl(instrumentableNode, sourceSection);
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    private void insertWrapperImpl(Node node, SourceSection sourceSection) {
        Node parent = node.getParent();
        if (parent instanceof WrapperNode) {
            // already wrapped, need to invalidate the wrapper something changed
            invalidateWrapperImpl((WrapperNode) parent, node);
            return;
        }
        ProbeNode probe = new ProbeNode(InstrumentationHandler.this, sourceSection);
        WrapperNode wrapper;
        if (node instanceof InstrumentableNode) {
            try {
                wrapper = ((InstrumentableNode) node).createWrapper(probe);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create wrapper of " + node, e);
            }
        } else {
            throw new AssertionError();
        }

        final Node wrapperNode = getWrapperNodeChecked(wrapper, node, parent);

        node.replace(wrapperNode, "Insert instrumentation wrapper node.");

        assert probe.getContext().validEventContext();
    }

    private static Node getWrapperNodeChecked(Object wrapper, Node node, Node parent) {
        if (wrapper == null) {
            throw new IllegalStateException("No wrapper returned for " + node + " of class " + node.getClass().getName());
        }
        if (!(wrapper instanceof Node)) {
            throw new IllegalStateException(String.format("Implementation of %s must be a subclass of %s.",
                            wrapper.getClass().getName(), Node.class.getSimpleName()));
        }

        final Node wrapperNode = (Node) wrapper;
        if (wrapperNode.getParent() != null) {
            throw new IllegalStateException(String.format("Instance of provided wrapper %s is already adopted by another parent: %s",
                            wrapper.getClass().getName(), wrapperNode.getParent().getClass().getName()));
        }
        if (parent == null) {
            throw new IllegalStateException(String.format("Instance of instrumentable node %s is not adopted by a parent.", node.getClass().getName()));
        }

        if (!NodeUtil.isReplacementSafe(parent, node, wrapperNode)) {
            throw new IllegalStateException(
                            String.format("WrapperNode implementation %s cannot be safely replaced in parent node class %s.", wrapperNode.getClass().getName(), parent.getClass().getName()));
        }
        return wrapperNode;
    }

    private <T extends ExecutionEventNodeFactory> EventBinding<T> attachFactory(AbstractInstrumenter instrumenter, SourceSectionFilter filter, SourceSectionFilter inputFilter, T factory) {
        return addExecutionBinding(new EventBinding.Source<>(instrumenter, filter, inputFilter, factory, true));
    }

    private <T extends ExecutionEventListener> EventBinding<T> attachListener(AbstractInstrumenter instrumenter, SourceSectionFilter filter, SourceSectionFilter inputFilter, T listener) {
        return addExecutionBinding(new EventBinding.Source<>(instrumenter, filter, inputFilter, listener, true));
    }

    private <T extends LoadSourceListener> EventBinding<T> attachSourceListener(AbstractInstrumenter abstractInstrumenter, SourceSectionFilter filter, T listener, boolean notifyLoaded) {
        return addSourceBinding(new EventBinding.Source<>(abstractInstrumenter, filter, null, listener, false), notifyLoaded);
    }

    private <T> EventBinding<T> attachSourceSectionListener(AbstractInstrumenter abstractInstrumenter, SourceSectionFilter filter, T listener, boolean notifyLoaded) {
        return addSourceSectionBinding(new EventBinding.Source<>(abstractInstrumenter, filter, null, listener, false), notifyLoaded);
    }

    private void visitLoadedSourceSections(AbstractInstrumenter abstractInstrumenter, SourceSectionFilter filter, LoadSourceSectionListener listener) {
        visitLoadedSourceSections(new EventBinding.Source<>(abstractInstrumenter, filter, null, listener, false));
    }

    private <T> EventBinding<T> attachExecuteSourceListener(AbstractInstrumenter abstractInstrumenter, SourceSectionFilter filter, T listener, boolean notifyLoaded) {
        return addSourceExecutionBinding(new EventBinding.Source<>(abstractInstrumenter, filter, null, listener, false), notifyLoaded);
    }

    private <T extends OutputStream> EventBinding<T> attachOutputConsumer(AbstractInstrumenter instrumenter, T stream, boolean errorOutput) {
        return addOutputBinding(new EventBinding<>(instrumenter, stream), errorOutput);
    }

    private <T extends AllocationListener> EventBinding<T> attachAllocationListener(AbstractInstrumenter instrumenter, AllocationEventFilter filter, T listener) {
        return addAllocationBinding(new EventBinding.Allocation<>(instrumenter, filter, listener));
    }

    private <T extends ContextsListener> EventBinding<T> attachContextsListener(AbstractInstrumenter instrumenter, T listener, boolean includeActiveContexts) {
        assert listener != null;
        return addContextsBinding(new EventBinding<>(instrumenter, listener), includeActiveContexts);
    }

    private <T extends ThreadsListener> EventBinding<T> attachThreadsListener(AbstractInstrumenter instrumenter, T listener, boolean includeStartedThreads) {
        assert listener != null;
        return addThreadsBinding(new EventBinding<>(instrumenter, listener), includeStartedThreads);
    }

    boolean hasContextBindings() {
        return !contextsBindings.isEmpty();
    }

    void notifyContextCreated(TruffleContext context) {
        for (EventBinding<? extends ContextsListener> binding : contextsBindings) {
            binding.getElement().onContextCreated(context);
        }
    }

    void notifyContextClosed(TruffleContext context) {
        for (EventBinding<? extends ContextsListener> binding : contextsBindings) {
            binding.getElement().onContextClosed(context);
        }
    }

    void notifyLanguageContextCreated(TruffleContext context, LanguageInfo language) {
        for (EventBinding<? extends ContextsListener> binding : contextsBindings) {
            binding.getElement().onLanguageContextCreated(context, language);
        }
    }

    void notifyLanguageContextInitialized(TruffleContext context, LanguageInfo language) {
        for (EventBinding<? extends ContextsListener> binding : contextsBindings) {
            binding.getElement().onLanguageContextInitialized(context, language);
        }
    }

    void notifyLanguageContextFinalized(TruffleContext context, LanguageInfo language) {
        for (EventBinding<? extends ContextsListener> binding : contextsBindings) {
            binding.getElement().onLanguageContextFinalized(context, language);
        }
    }

    void notifyLanguageContextDisposed(TruffleContext context, LanguageInfo language) {
        for (EventBinding<? extends ContextsListener> binding : contextsBindings) {
            binding.getElement().onLanguageContextDisposed(context, language);
        }
    }

    void notifyThreadStarted(TruffleContext context, Thread thread) {
        for (EventBinding<? extends ThreadsListener> binding : threadsBindings) {
            binding.getElement().onThreadInitialized(context, thread);
        }
    }

    void notifyThreadFinished(TruffleContext context, Thread thread) {
        for (EventBinding<? extends ThreadsListener> binding : threadsBindings) {
            binding.getElement().onThreadDisposed(context, thread);
        }
    }

    Set<Class<?>> getProvidedTags(TruffleLanguage<?> lang) {
        if (lang == null) {
            return Collections.emptySet();
        }
        Class<?> languageClass = lang.getClass();
        Set<Class<?>> tags = cachedProvidedTags.get(languageClass);
        if (tags == null) {
            ProvidedTags languageTags = languageClass.getAnnotation(ProvidedTags.class);
            List<Class<?>> languageTagsList = languageTags != null ? Arrays.asList(languageTags.value()) : Collections.<Class<?>> emptyList();
            tags = Collections.unmodifiableSet(new HashSet<>(languageTagsList));
            cachedProvidedTags.put(languageClass, tags);
        }
        return tags;
    }

    Set<Class<?>> getProvidedTags(Node root) {
        return getProvidedTags(InstrumentAccessor.nodesAccess().getLanguage(root.getRootNode()));
    }

    static boolean isInstrumentableNode(Node node) {
        if (node instanceof WrapperNode) {
            return false;
        }
        if (node instanceof InstrumentableNode) {
            return ((InstrumentableNode) node).isInstrumentable();
        } else {
            return false;
        }
    }

    static void trace(String message, Object... args) {
        PrintStream out = System.out;
        out.printf(message, args);
    }

    @SuppressWarnings("unchecked")
    private void visitRoot(RootNode root, final Node node, final AbstractNodeVisitor visitor, boolean forceRootBitComputation, boolean firstExecution) {
        if (TRACE) {
            trace("BEGIN: Visit root %s for %s%n", root.toString(), visitor);
        }

        visitor.instrumentationHandler = this;
        visitor.firstExecution = firstExecution;
        visitor.root = root;
        visitor.providedTags = getProvidedTags(root);
        visitor.materializeTags = (Set<Class<? extends Tag>>) (visitor.materializeLimitedTags == null ? visitor.providedTags : visitor.materializeLimitedTags);
        visitor.rootSourceSection = root.getSourceSection();
        visitor.rootBits = RootNodeBits.get(visitor.root);

        if (visitor.shouldVisit() || forceRootBitComputation) {
            if (forceRootBitComputation) {
                visitor.computingRootNodeBits = RootNodeBits.isUninitialized(visitor.rootBits) ? RootNodeBits.getAll() : visitor.rootBits;
            } else if (RootNodeBits.isUninitialized(visitor.rootBits)) {
                visitor.computingRootNodeBits = RootNodeBits.getAll();
            }

            if (TRACE) {
                trace("BEGIN: Traverse root %s for %s%n", root.toString(), visitor);
            }
            Lock lock = InstrumentAccessor.nodesAccess().getLock(node);
            try {
                lock.lock();
                visitor.visit(node);
            } finally {
                lock.unlock();
            }
            if (TRACE) {
                trace("END: Traverse root %s for %s%n", root.toString(), visitor);
            }

            if (!RootNodeBits.isUninitialized(visitor.computingRootNodeBits)) {
                RootNodeBits.set(visitor.root, visitor.computingRootNodeBits);
            }
        }

        if (TRACE) {
            trace("END: Visited root %s for %s%n", root.toString(), visitor);
        }
    }

    @SuppressWarnings("deprecation")
    static void removeWrapper(ProbeNode node) {
        if (TRACE) {
            trace("Remove wrapper for %s%n", node.getContext().getInstrumentedSourceSection());
        }
        WrapperNode wrapperNode = node.findWrapper();
        ((Node) wrapperNode).replace(wrapperNode.getDelegateNode());
    }

    @SuppressWarnings("deprecation")
    private static void invalidateWrapper(Node node) {
        Node parent = node.getParent();
        if (!(parent instanceof WrapperNode)) {
            // not yet wrapped
            return;
        }
        invalidateWrapperImpl((WrapperNode) parent, node);
    }

    private static void invalidateWrapperImpl(WrapperNode parent, Node node) {
        ProbeNode probeNode = parent.getProbeNode();
        if (TRACE) {
            SourceSection section = probeNode.getContext().getInstrumentedSourceSection();
            trace("Invalidate wrapper for %s, section %s %n", node, section);
        }
        if (probeNode != null) {
            probeNode.invalidate();
        }
    }

    @SuppressWarnings("unchecked")
    static boolean hasTagImpl(Set<Class<?>> providedTags, Node node, Class<?> tag) {
        if (providedTags.contains(tag)) {
            if (node instanceof InstrumentableNode) {
                return ((InstrumentableNode) node).hasTag((Class<? extends Tag>) tag);
            } else {
                return false;
            }
        }
        return false;
    }

    <T> T lookup(Object key, Class<T> type) {
        AbstractInstrumenter value = instrumenterMap.get(key);
        return value == null ? null : value.lookup(this, type);
    }

    AllocationReporter getAllocationReporter(LanguageInfo info) {
        AllocationReporter allocationReporter = new AllocationReporter(info);
        allocationReporters.add(allocationReporter);
        for (EventBinding.Allocation<? extends AllocationListener> binding : allocationBindings) {
            if (binding.getAllocationFilter().contains(info)) {
                allocationReporter.addListener(binding.getElement());
            }
        }
        return allocationReporter;
    }

    void patch(DispatchOutputStream newOut, DispatchOutputStream newErr, InputStream newIn) {
        this.out = newOut;
        this.err = newErr;
        this.in = newIn;
    }

    static void failInstrumentInitialization(Env env, String message, Throwable t) {
        Exception exception = new Exception(message, t);
        PrintStream stream = new PrintStream(env.err());
        exception.printStackTrace(stream);
    }

    private static WrapperNode getWrapperNode(Node node) {
        Node parent = node.getParent();
        return parent instanceof WrapperNode ? (WrapperNode) parent : null;
    }

    private abstract static class VisitOperation {
        /**
         * Scope of the operation in the AST. {@link Scope#ALL} means all nodes, i.e. both the
         * original nodes that existed when the visitor using the operation was initiated from the
         * root, and the new nodes in all the materialized subtrees that were created when nodes
         * were materialized. {@link Scope#ONLY_ORIGINAL} means only the original nodes and
         * {@link Scope#ONLY_MATERIALIZED} means only the new nodes in materialized subtrees. See
         * {@link BindingsVisitorBuilder} for an example.
         */
        enum Scope {
            ALL,
            ONLY_ORIGINAL,
            ONLY_MATERIALIZED
        }

        private final Scope scope;
        private final Collection<EventBinding.Source<?>> bindings;
        /*
         * True if this operation contains only one binding. The reason for storing this in a
         * separate field is that the bindings collection is either a singleton list or an async
         * collectionswhich does not support size(). Which one of those it is is know only at
         * construction time.
         */
        private final boolean singleBindingOperation;
        /*
         * If true, the operation is performed for each bindings, which, for instance, is not
         * necessary for the InsertWrapperOperation as wrapper needs to be inserted only once.
         */
        private final boolean performForEachBinding;

        VisitOperation(Scope scope, EventBinding.Source<?> binding) {
            this(scope, Collections.singletonList(binding), true, true);
        }

        VisitOperation(Scope scope, Collection<EventBinding.Source<?>> bindings, boolean performForEachBinding) {
            this(scope, bindings, false, performForEachBinding);
        }

        VisitOperation(Scope scope, Collection<EventBinding.Source<?>> bindings, boolean singleBindingOperation, boolean performForEachBinding) {
            this.scope = scope;
            this.bindings = bindings;
            this.singleBindingOperation = singleBindingOperation;
            this.performForEachBinding = performForEachBinding;
        }

        protected abstract void perform(EventBinding.Source<?> binding, Node node, SourceSection section);
    }

    private class InsertWrapperOperation extends VisitOperation {
        InsertWrapperOperation(Scope scope, EventBinding.Source<?> binding) {
            super(scope, binding);
        }

        InsertWrapperOperation(Scope scope, Collection<EventBinding.Source<?>> bindings) {
            super(scope, bindings, false);
        }

        @Override
        protected void perform(EventBinding.Source<?> binding, Node node, SourceSection section) {
            insertWrapper(node, section);
        }
    }

    private static class NotifyLoadedOperation extends VisitOperation {
        NotifyLoadedOperation(Scope scope, EventBinding.Source<?> binding) {
            super(scope, binding);
        }

        NotifyLoadedOperation(Scope scope, Collection<EventBinding.Source<?>> bindings) {
            super(scope, bindings, true);
        }

        @Override
        protected void perform(EventBinding.Source<?> binding, Node node, SourceSection section) {
            notifySourceSectionLoaded(binding, node, section);
        }
    }

    private static class DisposeWrapperOperation extends VisitOperation {
        DisposeWrapperOperation(Scope scope, EventBinding.Source<?> binding) {
            super(scope, binding);
        }

        DisposeWrapperOperation(Scope scope, Collection<EventBinding.Source<?>> bindings) {
            super(scope, bindings, false);
        }

        @Override
        protected void perform(EventBinding.Source<?> binding, Node node, SourceSection section) {
            invalidateWrapper(node);
        }
    }

    /**
     * Build BindingsVisitor with specified operations.
     * <p>
     * Usage example:
     * <p>
     * Build visitor with NotifyLoadedOperation operation for all source section bindings, scope is
     * all nodes, used when an AST is first loaded:
     * 
     * <pre>
     * BindingsVisitorBuilder visitorBuilder = new BindingsVisitorBuilder();
     * visitorBuilder.addNotifyLoadedOperationForAllBindings(VisitOperation.Scope.ALL);
     * visitorBuilder.buildVisitor();
     * </pre>
     * <p>
     * Build visitor with two InsertWrapperOperation operations and one NotifyLoadedOperation
     * operation. The visitor is used when a new execution binding is added. The first
     * InsertWrapperOperation operation is only for the new execution binding and its scope is only
     * original nodes. The second InsertWrapperOperation operation is for all execution bindings
     * (including the new one) and its scope is new materialized subtrees only. The
     * NotifyLoadedOperation operation is for all source section bindings and its scope is new
     * materialized subtrees only. The new materialized subtrees are not instrumented at all, that
     * is why we have to apply all bindings there. For the original nodes, applying just the new
     * execution binding is sufficient, because the other bindings were applied when they were
     * added.
     *
     * <pre>
     * BindingsVisitorBuilder visitorBuilder = new BindingsVisitorBuilder();
     * visitorBuilder.addInsertWrapperOperationForBinding(VisitOperation.Scope.ONLY_ORIGINAL, binding);
     * visitorBuilder.addInsertWrapperOperationForAllBindings(VisitOperation.Scope.ONLY_MATERIALIZED);
     * visitorBuilder.addNotifyLoadedOperationForAllBindings(VisitOperation.Scope.ONLY_MATERIALIZED);
     * visitorBuilder.buildVisitor();
     * </pre>
     */
    private class BindingsVisitorBuilder {
        List<VisitOperation> operations = new ArrayList<>();
        boolean shouldMaterializeSyntaxNodes;

        BindingsVisitorBuilder addNotifyLoadedOperationForAllBindings(VisitOperation.Scope scope) {
            operations.add(new NotifyLoadedOperation(scope, sourceSectionBindings));
            shouldMaterializeSyntaxNodes = true;
            return this;
        }

        BindingsVisitorBuilder addNotifyLoadedOperationForBinding(VisitOperation.Scope scope, EventBinding.Source<?> binding) {
            operations.add(new NotifyLoadedOperation(scope, binding));
            shouldMaterializeSyntaxNodes = true;
            return this;
        }

        BindingsVisitorBuilder addInsertWrapperOperationForAllBindings(VisitOperation.Scope scope) {
            operations.add(new InsertWrapperOperation(scope, executionBindings));
            shouldMaterializeSyntaxNodes = true;
            return this;
        }

        BindingsVisitorBuilder addInsertWrapperOperationForBinding(VisitOperation.Scope scope, EventBinding.Source<?> binding) {
            operations.add(new InsertWrapperOperation(scope, binding));
            shouldMaterializeSyntaxNodes = true;
            return this;
        }

        BindingsVisitorBuilder addDisposeWrapperOperationForBinding(EventBinding.Source<?> binding) {
            operations.add(new DisposeWrapperOperation(VisitOperation.Scope.ALL, binding));
            return this;
        }

        BindingsVisitorBuilder addDisposeWrapperOperationForBindings(Collection<EventBinding.Source<?>> bindings) {
            operations.add(new DisposeWrapperOperation(VisitOperation.Scope.ALL, bindings));
            return this;
        }

        BindingsVisitor buildVisitor() {
            return new BindingsVisitor(shouldMaterializeSyntaxNodes, Collections.unmodifiableList(operations));
        }
    }

    private abstract static class AbstractNodeVisitor implements NodeVisitor {

        InstrumentationHandler instrumentationHandler;
        RootNode root;
        SourceSection rootSourceSection;
        Set<Class<?>> providedTags;
        Set<?> materializeLimitedTags;
        boolean firstExecution = false;

        /* cached root bits read from the root node. value is reliable. */
        int rootBits;
        /* temporary field for currently computing root bits. value is not reliable. */
        int computingRootNodeBits;
        /* flag set on when visiting a retired subtree that was replaced by materialization */
        boolean visitingRetiredNodes;
        /* flag set on when visiting a new subtree that was created by materialization */
        boolean visitingMaterialized;

        private final boolean shouldMaterializeSyntaxNodes;
        Set<Class<? extends Tag>> materializeTags;

        AbstractNodeVisitor(boolean shouldMaterializeSyntaxNodes) {
            this.shouldMaterializeSyntaxNodes = shouldMaterializeSyntaxNodes;
        }

        abstract boolean shouldVisit();

        private void computeRootBits(SourceSection sourceSection) {
            int bits = computingRootNodeBits;
            if (RootNodeBits.isUninitialized(bits)) {
                return;
            }

            if (sourceSection != null) {
                if (RootNodeBits.isNoSourceSection(bits)) {
                    bits = RootNodeBits.setHasSourceSection(bits);
                }
                if (rootSourceSection != null) {
                    if (RootNodeBits.isSourceSectionsHierachical(bits)) {
                        if (sourceSection.getCharIndex() < rootSourceSection.getCharIndex() //
                                        || sourceSection.getCharEndIndex() > rootSourceSection.getCharEndIndex()) {
                            bits = RootNodeBits.setSourceSectionsUnstructured(bits);
                        }
                    }
                    if (RootNodeBits.isSameSource(bits) && rootSourceSection.getSource() != sourceSection.getSource()) {
                        bits = RootNodeBits.setHasDifferentSource(bits);
                    }
                } else {
                    bits = RootNodeBits.setSourceSectionsUnstructured(bits);
                    bits = RootNodeBits.setHasDifferentSource(bits);
                }
            }
            computingRootNodeBits = bits;
        }

        private Node savedParent;
        private SourceSection savedParentSourceSection;

        public final boolean visit(Node originalNode) {
            Node node = originalNode;
            SourceSection sourceSection = node.getSourceSection();
            boolean instrumentable = InstrumentationHandler.isInstrumentableNode(node);
            Node previousParent = null;
            SourceSection previousParentSourceSection = null;
            if (instrumentable) {
                computeRootBits(sourceSection);
                boolean hasRetiredNodes = visitPreviouslyRetiredNodes(node);
                if (!visitingRetiredNodes) {
                    node = materialize(node, sourceSection, originalNode);
                    if (saveAndVisitNewlyRetiredNode(node, sourceSection, originalNode)) {
                        hasRetiredNodes = true;
                    }
                    if (!hasRetiredNodes) {
                        clearRetiredNodeReference(node);
                    }
                }
                visitInstrumentable(this.savedParent, this.savedParentSourceSection, node, sourceSection);

                previousParent = this.savedParent;
                previousParentSourceSection = this.savedParentSourceSection;
                this.savedParent = node;
                this.savedParentSourceSection = sourceSection;
            }
            /*
             * Although it is required that the materialized subtree is completely new and fully
             * materialized, it is not strictly enforced, so it is possible that there will be
             * further materializations in the materialized subtree, and so we must store the
             * previous state of visitingMaterialized and restore it when we return from the
             * recursive call.
             */
            boolean wasVisitingMaterialized = visitingMaterialized;
            if (node != originalNode) {
                visitingMaterialized = true;
            }
            try {
                NodeUtil.forEachChild(node, this);
            } finally {
                visitingMaterialized = wasVisitingMaterialized;
                if (instrumentable) {
                    this.savedParent = previousParent;
                    this.savedParentSourceSection = previousParentSourceSection;
                }
            }
            return true;
        }

        private Node materialize(Node node, SourceSection sourceSection, Node originalNode) {
            Node materializedNode = materializeSyntaxNodes(node, sourceSection);
            assert !visitingMaterialized || materializedNode == originalNode : "New tree should be fully materialized!";
            assert materializedNode == materializeSyntaxNodes(materializedNode, sourceSection) : "Node must not be materialized multiple times for the same set of tags!";
            return materializedNode;
        }

        private boolean saveAndVisitNewlyRetiredNode(Node node, SourceSection sourceSection, Node originalNode) {
            if (!firstExecution && node != originalNode) {
                assert materializeTags != null : "Materialize tags must not be null when materialization happened.";
                /*
                 * If node is not the same as the originalNode, the original node is retired and we
                 * keep a reference to the retired node in the probe node of the wrapper of the
                 * materialized node that replaced the retired node. If the wrapper does not exist
                 * yet, we create it, otherwise the reference to the retired node would be lost.
                 */
                WrapperNode wrapperNode = getWrapperNode(node);
                if (wrapperNode == null) {
                    instrumentationHandler.insertWrapper(node, sourceSection);
                }
                wrapperNode = getWrapperNode(node);
                assert wrapperNode != null : "Node must have an instrumentation wrapper at this point!";
                wrapperNode.getProbeNode().setRetiredNode(originalNode, materializeTags);
                /*
                 * We also need to traverse all children of the retired node that was just retired.
                 * This is necessary if the retired node is still currently executing and does not
                 * yet see the new (materialized) node. Unfortunately we don't know reliably whether
                 * we are currently executing that is why we always need to instrument the retired
                 * node as well. This is especially problematic for long or infinite loops in
                 * combination with cancel events.
                 */
                visitRetiredNodes(originalNode);
                return true;
            }
            return false;
        }

        private static void clearRetiredNodeReference(Node node) {
            // There are no retired nodes the subtrees of which we need to traverse.
            WrapperNode wrapperNode = getWrapperNode(node);
            if (wrapperNode != null) {
                wrapperNode.getProbeNode().clearRetiredNodeReference();
                // At this point the probe node might already have no chain, and it
                // also might not be updated further, and so only invalidation makes
                // sure the wrapper gets eventually removed.
                invalidateWrapperImpl(wrapperNode, node);
            }
        }

        /*
         * We need to traverse all the retired subtrees that are no longer reachable in the AST due
         * to previous materializations.
         */
        private boolean visitPreviouslyRetiredNodes(Node node) {
            if (!firstExecution) {
                WrapperNode wrapperNode = getWrapperNode(node);
                ProbeNode.RetiredNodeReference retiredNodeReference = (wrapperNode != null ? wrapperNode.getProbeNode().getRetiredNodeReference() : null);
                if (retiredNodeReference != null) {
                    boolean hasRetiredNodes = false;
                    while (retiredNodeReference != null) {
                        Node nodeRefNode = retiredNodeReference.getNode();
                        if (nodeRefNode != null) {
                            hasRetiredNodes = true;
                            visitRetiredNodes(nodeRefNode);
                        }
                        retiredNodeReference = retiredNodeReference.next;
                    }
                    return hasRetiredNodes;
                }
            }
            return false;
        }

        /**
         * Visit retired subtree. The retired subtree might have references to previously retired
         * subtrees, so we must store the previous state of visitingRetiredNodes and restore it when
         * we return form the recursive call.
         */
        private void visitRetiredNodes(Node retiredSubtreeRoot) {
            boolean wasVisitingRetiredNodes = visitingRetiredNodes;
            visitingRetiredNodes = true;
            try {
                NodeUtil.forEachChild(retiredSubtreeRoot, this);
            } finally {
                visitingRetiredNodes = wasVisitingRetiredNodes;
            }
        }

        private Node materializeSyntaxNodes(Node instrumentableNode, SourceSection sourceSection) {
            if (!shouldMaterializeSyntaxNodes) {
                return instrumentableNode;
            }
            if (instrumentableNode instanceof InstrumentableNode) {
                assert materializeTags != null : "Materialize tags must not be null before materialization.";
                InstrumentableNode currentNode = (InstrumentableNode) instrumentableNode;
                assert currentNode.isInstrumentable();
                InstrumentableNode materializedNode = currentNode.materializeInstrumentableNodes(materializeTags);
                if (currentNode != materializedNode) {
                    if (!(materializedNode instanceof Node)) {
                        throw new IllegalStateException("The returned materialized syntax node is not a Truffle Node.");
                    }
                    if (((Node) materializedNode).getParent() != null) {
                        throw new IllegalStateException("The returned materialized syntax node is already adopted.");
                    }
                    SourceSection newSourceSection = ((Node) materializedNode).getSourceSection();
                    if (!Objects.equals(sourceSection, newSourceSection)) {
                        throw new IllegalStateException(String.format("The source section of the materialized syntax node must match the source section of the original node. %s != %s.", sourceSection,
                                        newSourceSection));
                    }

                    Node currentParent = ((Node) currentNode).getParent();
                    // The current parent is a wrapper. We need to replace the wrapper.
                    if (currentParent instanceof WrapperNode && !NodeUtil.isReplacementSafe(currentParent, instrumentableNode, (Node) materializedNode)) {
                        ProbeNode probe = ((WrapperNode) currentParent).getProbeNode();
                        WrapperNode wrapper = materializedNode.createWrapper(probe);
                        final Node wrapperNode = getWrapperNodeChecked(wrapper, (Node) materializedNode, currentParent.getParent());
                        currentParent.replace(wrapperNode, "Insert instrumentation wrapper node.");
                        return (Node) materializedNode;
                    } else {
                        return ((Node) currentNode).replace((Node) materializedNode);
                    }
                }
            }
            return instrumentableNode;
        }

        protected abstract void visitInstrumentable(Node parentInstrumentable, SourceSection parentSourceSection, Node instrumentableNode, SourceSection sourceSection);

    }

    private static class BindingsVisitor extends AbstractNodeVisitor {
        private final List<VisitOperation> operations;
        private final boolean singleBindingOptimization;

        BindingsVisitor(boolean shouldMaterializeSyntaxNodes, List<VisitOperation> operations) {
            super(shouldMaterializeSyntaxNodes);
            this.operations = operations;
            int singleBindingOperations = 0;
            int multiBindingOriginalTreeOperations = 0;
            for (VisitOperation operation : operations) {
                if (operation.singleBindingOperation) {
                    singleBindingOperations++;
                } else if (operation.scope == VisitOperation.Scope.ALL || operation.scope == VisitOperation.Scope.ONLY_ORIGINAL) {
                    multiBindingOriginalTreeOperations++;
                }
            }
            this.singleBindingOptimization = ((operations.size() == 1 && singleBindingOperations == 1) ||
                            (singleBindingOperations == 1 && multiBindingOriginalTreeOperations == 0));

            Set<Class<?>> compoundTags = null; // null means all provided tags by the language
            for (VisitOperation operation : operations) {
                for (EventBinding.Source<?> sourceBinding : operation.bindings) {
                    Set<Class<?>> limitedTags = sourceBinding.getLimitedTags();
                    if (limitedTags == null) {
                        compoundTags = null;
                        break;
                    } else {
                        if (compoundTags == null) {
                            compoundTags = new HashSet<>();
                        }
                        compoundTags.addAll(limitedTags);
                    }
                }
            }
            this.materializeLimitedTags = compoundTags != null ? Collections.unmodifiableSet(compoundTags) : null;
        }

        @Override
        boolean shouldVisit() {
            if (operations.isEmpty()) {
                return false;
            }
            RootNode localRoot = root;
            SourceSection localRootSourceSection = rootSourceSection;
            int localRootBits = rootBits;

            for (VisitOperation operation : operations) {
                /*
                 * If singleBindingOptimization == true then there is exactly one single binding
                 * operation and it is the only operation that operates in the original tree, so if
                 * the tree should not be visited for this binding, it should not be visited at all,
                 * because no new materialized subtrees would be created and so there would be no
                 * nodes the other operations could operate on.
                 */
                if (!singleBindingOptimization || operation.singleBindingOperation) {
                    for (EventBinding.Source<?> binding : operation.bindings) {
                        if (binding.isInstrumentedRoot(providedTags, localRoot, localRootSourceSection, localRootBits)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        boolean shouldPerformForBinding(VisitOperation operation, EventBinding.Source<?> binding, Node parentInstrumentable, SourceSection parentSourceSection, Node instrumentableNode,
                        SourceSection sourceSection) {
            if (singleBindingOptimization && operation.singleBindingOperation) {
                return binding.isInstrumentedLeaf(providedTags, instrumentableNode, sourceSection) ||
                                binding.isChildInstrumentedLeaf(providedTags, root, parentInstrumentable, parentSourceSection, instrumentableNode, sourceSection);
            } else {
                return binding.isInstrumentedFull(providedTags, root, instrumentableNode, sourceSection) ||
                                binding.isChildInstrumentedFull(providedTags, root, parentInstrumentable, parentSourceSection, instrumentableNode, sourceSection);
            }
        }

        @Override
        protected void visitInstrumentable(Node parentInstrumentable, SourceSection parentSourceSection, Node instrumentableNode, SourceSection sourceSection) {
            for (VisitOperation operation : operations) {
                if (operation.scope == VisitOperation.Scope.ALL ||
                                (!visitingMaterialized && operation.scope == VisitOperation.Scope.ONLY_ORIGINAL) ||
                                (visitingMaterialized && operation.scope == VisitOperation.Scope.ONLY_MATERIALIZED)) {
                    for (EventBinding.Source<?> binding : operation.bindings) {
                        if (shouldPerformForBinding(operation, binding, parentInstrumentable, parentSourceSection, instrumentableNode, sourceSection)) {
                            if (TRACE) {
                                traceFilterCheck("hit", instrumentableNode, sourceSection);
                            }
                            operation.perform(binding, instrumentableNode, sourceSection);
                            if (!operation.performForEachBinding) {
                                break;
                            }
                        } else {
                            if (TRACE) {
                                traceFilterCheck("miss", instrumentableNode, sourceSection);
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void traceFilterCheck(String result, Node instrumentableNode, SourceSection sourceSection) {
        trace("  Filter %4s node:%s section:%s %n", result, instrumentableNode, sourceSection);
    }

    /**
     * Provider of instrumentation services for {@linkplain TruffleInstrument external clients} of
     * instrumentation.
     */
    final class InstrumentClientInstrumenter extends AbstractInstrumenter {

        private final String instrumentClassName;
        private Object[] services;
        TruffleInstrument instrument;
        private final Env env;

        InstrumentClientInstrumenter(Env env, String instrumentClassName) {
            this.instrumentClassName = instrumentClassName;
            this.env = env;
        }

        @Override
        boolean isInstrumentableSource(Source source) {
            return true;
        }

        @Override
        boolean isInstrumentableRoot(RootNode rootNode) {
            return true;
        }

        @Override
        public Set<Class<?>> queryTags(Node node) {
            return queryTagsImpl(node, null);
        }

        @Override
        void verifyFilter(SourceSectionFilter filter) {
        }

        String getInstrumentClassName() {
            return instrumentClassName;
        }

        Env getEnv() {
            return env;
        }

        void create(String[] expectedServices) {
            if (TRACE) {
                trace("Create instrument %s class %s %n", instrument, instrumentClassName);
            }
            services = env.onCreate(instrument);
            if (expectedServices != null && !TruffleOptions.AOT) {
                checkServices(expectedServices);
            }
            if (TRACE) {
                trace("Created instrument %s class %s %n", instrument, instrumentClassName);
            }
        }

        private boolean checkServices(String[] expectedServices) {
            LOOP: for (String name : expectedServices) {
                for (Object obj : services) {
                    if (findType(name, obj.getClass())) {
                        continue LOOP;
                    }
                }
                failInstrumentInitialization(env, String.format("%s declares service %s but doesn't register it", instrumentClassName, name), null);
            }
            return true;
        }

        private boolean findType(String name, Class<?> type) {
            if (type == null) {
                return false;
            }
            if (type.getName().equals(name) || (type.getCanonicalName() != null && type.getCanonicalName().equals(name))) {
                return true;
            }
            if (findType(name, type.getSuperclass())) {
                return true;
            }
            for (Class<?> inter : type.getInterfaces()) {
                if (findType(name, inter)) {
                    return true;
                }
            }
            return false;
        }

        boolean isInitialized() {
            return instrument != null;
        }

        TruffleInstrument getInstrument() {
            return instrument;
        }

        @Override
        public <T extends ContextsListener> EventBinding<T> attachContextsListener(T listener, boolean includeActiveContexts) {
            return InstrumentationHandler.this.attachContextsListener(this, listener, includeActiveContexts);
        }

        @Override
        public <T extends ThreadsListener> EventBinding<T> attachThreadsListener(T listener, boolean includeStartedThreads) {
            return InstrumentationHandler.this.attachThreadsListener(this, listener, includeStartedThreads);
        }

        @Override
        void doFinalize() {
            instrument.onFinalize(env);
        }

        @Override
        void dispose() {
            instrument.onDispose(env);
        }

        @Override
        <T> T lookup(InstrumentationHandler handler, Class<T> type) {
            if (services != null) {
                for (Object service : services) {
                    if (type.isInstance(service)) {
                        return type.cast(service);
                    }
                }
            }
            return null;
        }

    }

    /**
     * Provider of instrumentation services for {@linkplain TruffleLanguage language
     * implementations}.
     */
    final class EngineInstrumenter extends AbstractInstrumenter {

        @Override
        void doFinalize() {
        }

        @Override
        void dispose() {
        }

        @Override
        <T> T lookup(InstrumentationHandler handler, Class<T> type) {
            return null;
        }

        @Override
        boolean isInstrumentableRoot(RootNode rootNode) {
            return true;
        }

        @Override
        boolean isInstrumentableSource(Source source) {
            return true;
        }

        @Override
        void verifyFilter(SourceSectionFilter filter) {
        }

        @Override
        public Set<Class<?>> queryTags(Node node) {
            return queryTagsImpl(node, null);
        }

        @Override
        public <T extends ContextsListener> EventBinding<T> attachContextsListener(T listener, boolean includeActiveContexts) {
            throw new UnsupportedOperationException("Not supported in engine instrumenter.");
        }

        @Override
        public <T extends ThreadsListener> EventBinding<T> attachThreadsListener(T listener, boolean includeStartedThreads) {
            throw new UnsupportedOperationException("Not supported in engine instrumenter.");
        }

    }

    /**
     * Provider of instrumentation services for {@linkplain TruffleLanguage language
     * implementations}.
     */
    final class LanguageClientInstrumenter<T> extends AbstractInstrumenter {

        private final LanguageInfo languageInfo;
        private final TruffleLanguage<?> language;

        LanguageClientInstrumenter(TruffleLanguage<?> language) {
            this.language = language;
            this.languageInfo = InstrumentAccessor.langAccess().getLanguageInfo(language);
        }

        @Override
        boolean isInstrumentableSource(Source source) {
            String mimeType = source.getMimeType();
            if (mimeType == null) {
                return false;
            }
            return languageInfo.getMimeTypes().contains(mimeType);
        }

        @Override
        boolean isInstrumentableRoot(RootNode node) {
            LanguageInfo langInfo = node.getLanguageInfo();
            if (langInfo == null) {
                return false;
            }
            if (langInfo != languageInfo) {
                return false;
            }
            return true;
        }

        @Override
        public Set<Class<?>> queryTags(Node node) {
            return queryTagsImpl(node, languageInfo);
        }

        @Override
        void verifyFilter(SourceSectionFilter filter) {
            Set<Class<?>> providedTags = getProvidedTags(language);
            // filters must not reference tags not declared in @RequiredTags
            Set<Class<?>> referencedTags = filter.getReferencedTags();
            if (!providedTags.containsAll(referencedTags)) {
                Set<Class<?>> missingTags = new HashSet<>(referencedTags);
                missingTags.removeAll(providedTags);
                Set<Class<?>> allTags = new LinkedHashSet<>(providedTags);
                allTags.addAll(missingTags);
                StringBuilder builder = new StringBuilder("{");
                String sep = "";
                for (Class<?> tag : allTags) {
                    builder.append(sep);
                    builder.append(tag.getSimpleName());
                    sep = ", ";
                }
                builder.append("}");
                throw new IllegalArgumentException(String.format("The attached filter %s references the following tags %s which are not declared as provided by the language. " +
                                "To fix this annotate the language class %s with @%s(%s).",
                                filter, missingTags, language.getClass().getName(), ProvidedTags.class.getSimpleName(), builder));
            }
        }

        @Override
        public <S extends ContextsListener> EventBinding<S> attachContextsListener(S listener, boolean includeActiveContexts) {
            throw new UnsupportedOperationException("Not supported in language instrumenter.");
        }

        @Override
        public <S extends ThreadsListener> EventBinding<S> attachThreadsListener(S listener, boolean includeStartedThreads) {
            throw new UnsupportedOperationException("Not supported in language instrumenter.");
        }

        @Override
        void doFinalize() {
            // nothing to do
        }

        @Override
        void dispose() {
            // nothing to do
        }

        @Override
        <S> S lookup(InstrumentationHandler handler, Class<S> type) {
            return null;
        }

    }

    /**
     * Shared implementation of instrumentation services for clients whose requirements and
     * privileges may vary.
     */
    abstract class AbstractInstrumenter extends Instrumenter {

        abstract void doFinalize();

        abstract void dispose();

        abstract <T> T lookup(InstrumentationHandler handler, Class<T> type);

        public void disposeBinding(EventBinding<?> binding) {
            InstrumentationHandler.this.disposeBinding(binding);
        }

        abstract boolean isInstrumentableRoot(RootNode rootNode);

        abstract boolean isInstrumentableSource(Source source);

        final Set<Class<?>> queryTagsImpl(Node node, LanguageInfo onlyLanguage) {
            Objects.requireNonNull(node);
            if (!InstrumentationHandler.isInstrumentableNode(node)) {
                return Collections.emptySet();
            }

            RootNode root = node.getRootNode();
            if (root == null) {
                return Collections.emptySet();
            }

            if (onlyLanguage != null && root.getLanguageInfo() != onlyLanguage) {
                throw new IllegalArgumentException("The language instrumenter cannot query tags of nodes of other languages.");
            }
            Set<Class<?>> providedTags = getProvidedTags(root);
            if (providedTags.isEmpty()) {
                return Collections.emptySet();
            }

            Set<Class<?>> tags = new HashSet<>();
            for (Class<?> providedTag : providedTags) {
                if (hasTagImpl(providedTags, node, providedTag)) {
                    tags.add(providedTag);
                }
            }
            return Collections.unmodifiableSet(tags);
        }

        @Override
        public final ExecutionEventNode lookupExecutionEventNode(Node node, EventBinding<?> binding) {
            if (!InstrumentationHandler.isInstrumentableNode(node)) {
                return null;
            }
            Node p = node.getParent();
            if (p instanceof WrapperNode) {
                WrapperNode w = (WrapperNode) p;
                return w.getProbeNode().lookupExecutionEventNode(binding);
            } else {
                return null;
            }
        }

        @Override
        public <T extends ExecutionEventNodeFactory> EventBinding<T> attachExecutionEventFactory(SourceSectionFilter filter, SourceSectionFilter inputFilter, T factory) {
            verifyFilter(filter);
            return InstrumentationHandler.this.attachFactory(this, filter, inputFilter, factory);
        }

        @SuppressWarnings("deprecation")
        @Override
        public <T extends ExecutionEventListener> EventBinding<T> attachExecutionEventListener(SourceSectionFilter filter, SourceSectionFilter inputFilter, T listener) {
            verifyFilter(filter);
            return InstrumentationHandler.this.attachListener(this, filter, inputFilter, listener);
        }

        @Override
        @SuppressWarnings("deprecation")
        public <T extends LoadSourceListener> EventBinding<T> attachLoadSourceListener(SourceSectionFilter filter, T listener, boolean includeExistingSources) {
            verifySourceOnly(filter);
            verifyFilter(filter);
            return InstrumentationHandler.this.attachSourceListener(this, filter, listener, includeExistingSources);
        }

        @Override
        public <T extends LoadSourceListener> EventBinding<T> attachLoadSourceListener(SourceFilter filter, T listener, boolean notifyLoaded) {
            SourceSectionFilter sectionsFilter = SourceSectionFilter.newBuilder().sourceFilter(filter).build();
            return attachLoadSourceListener(sectionsFilter, listener, notifyLoaded);
        }

        @Override
        public <T extends LoadSourceSectionListener> EventBinding<T> attachLoadSourceSectionListener(SourceSectionFilter filter, T listener, boolean notifyLoaded) {
            verifyFilter(filter);
            return InstrumentationHandler.this.attachSourceSectionListener(this, filter, listener, notifyLoaded);
        }

        @Override
        public void visitLoadedSourceSections(SourceSectionFilter filter, LoadSourceSectionListener listener) {
            verifyFilter(filter);
            InstrumentationHandler.this.visitLoadedSourceSections(this, filter, listener);
        }

        @Override
        public <T extends ExecuteSourceListener> EventBinding<T> attachExecuteSourceListener(SourceFilter filter, T listener, boolean notifyLoaded) {
            SourceSectionFilter sectionsFilter = SourceSectionFilter.newBuilder().sourceFilter(filter).build();
            return InstrumentationHandler.this.attachExecuteSourceListener(this, sectionsFilter, listener, notifyLoaded);
        }

        @Override
        public <T extends AllocationListener> EventBinding<T> attachAllocationListener(AllocationEventFilter filter, T listener) {
            return InstrumentationHandler.this.attachAllocationListener(this, filter, listener);
        }

        @Override
        public <T extends OutputStream> EventBinding<T> attachOutConsumer(T stream) {
            return InstrumentationHandler.this.attachOutputConsumer(this, stream, false);
        }

        @Override
        public <T extends OutputStream> EventBinding<T> attachErrConsumer(T stream) {
            return InstrumentationHandler.this.attachOutputConsumer(this, stream, true);
        }

        private void verifySourceOnly(SourceSectionFilter filter) {
            if (!filter.isSourceOnly()) {
                throw new IllegalArgumentException(String.format("The attached filter %s uses filters that require source sections to verifiy. " +
                                "Source listeners can only use filter critera based on Source objects like mimeTypeIs or sourceIs.", filter));
            }
        }

        abstract void verifyFilter(SourceSectionFilter filter);

    }

    /**
     * A list collection data structure that is optimized for fast non-blocking traversals. There is
     * adds and no explicit removal. Removals are based on a side effect of the element, by
     * returning <code>null</code> in {@link AbstractAsyncCollection#unwrap(Object)}. It is not
     * possible to reliably query the {@link AbstractAsyncCollection#size()} of the collection,
     * therefore it throws an {@link UnsupportedOperationException}.
     */
    private abstract static class AbstractAsyncCollection<T, R> extends AbstractCollection<R> {
        /*
         * We use an atomic reference list as we don't want to see holes in the array when appending
         * to it. This allows us to use null as a safe terminator for the array.
         */
        private volatile AtomicReferenceArray<T> values;

        /*
         * Size can be non volatile as it is not exposed or used for traversal.
         */
        private int nextInsertionIndex;

        AbstractAsyncCollection(int initialCapacity) {
            if (initialCapacity <= 0) {
                throw new IllegalArgumentException("Invalid initial capacity " + initialCapacity);
            }
            this.values = new AtomicReferenceArray<>(initialCapacity);
        }

        @Override
        public final synchronized boolean add(R reference) {
            T wrappedElement = wrap(reference);
            if (wrappedElement == null) {
                // fail early
                throw new NullPointerException();
            }
            if (nextInsertionIndex >= values.length()) {
                compact();
            }
            values.set(nextInsertionIndex++, wrappedElement);
            return true;
        }

        @Override
        public int size() {
            // size cannot be supported reliably
            throw new UnsupportedOperationException();
        }

        @Override
        public final boolean isEmpty() {
            return values.get(0) == null;
        }

        protected abstract T wrap(R element);

        protected abstract R unwrap(T element);

        private void compact() {
            AtomicReferenceArray<T> localValues = values;
            int liveElements = 0;
            /*
             * We count the still alive elements.
             */
            for (int i = 0; i < localValues.length(); i++) {
                T ref = localValues.get(i);
                if (ref == null) {
                    break;
                }
                if (unwrap(ref) != null) {
                    liveElements++;
                }
            }

            /*
             * We ensure that the capacity after compaction is always twice as big as the number of
             * live elements. This can make the array grow or shrink as needed.
             */
            AtomicReferenceArray<T> newValues = new AtomicReferenceArray<>(Math.max(liveElements * 2, 8));
            int index = 0;
            for (int i = 0; i < localValues.length(); i++) {
                T ref = localValues.get(i);
                if (ref == null) {
                    break;
                }
                if (unwrap(ref) != null) {
                    newValues.set(index++, ref);
                }
            }

            this.nextInsertionIndex = index;
            this.values = newValues;
        }

        /**
         * Returns an iterator which can be traversed without a lock. The iterator that is
         * constructed is not sequentially consistent. In other words, the user of the iterator may
         * observe values that were added after the iterator was created.
         */
        @Override
        public Iterator<R> iterator() {
            return new Iterator<R>() {

                /*
                 * We need to capture the values field in the iterator to have a consistent view on
                 * the data while iterating.
                 */
                private final AtomicReferenceArray<T> values = AbstractAsyncCollection.this.values;
                private int index;
                private R queuedNext;

                public boolean hasNext() {
                    R next = queuedNext;
                    if (next == null) {
                        next = queueNext();
                        queuedNext = next;
                    }
                    return next != null;
                }

                private R queueNext() {
                    int localIndex = index;
                    AtomicReferenceArray<T> array = values;
                    while (true) {
                        if (localIndex >= array.length()) {
                            return null;
                        }
                        T localValue = array.get(localIndex);
                        if (localValue == null) {
                            return null;
                        }
                        localIndex++;
                        R alive = unwrap(localValue);
                        if (alive == null) {
                            continue;
                        }
                        index = localIndex;
                        return alive;
                    }
                }

                public R next() {
                    R next = queuedNext;
                    if (next == null) {
                        next = queueNext();
                        if (next == null) {
                            throw new NoSuchElementException();
                        }
                    }
                    queuedNext = null;
                    return next;
                }

                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

    }

    /**
     * An async list implementation that removes elements whenever a binding was disposed.
     */
    private static final class EventBindingList<EB extends EventBinding<?>> extends AbstractAsyncCollection<EB, EB> {

        EventBindingList(int initialCapacity) {
            super(initialCapacity);
        }

        @Override
        protected EB wrap(EB element) {
            return element;
        }

        @Override
        protected EB unwrap(EB element) {
            if (element.isDisposed()) {
                return null;
            }
            return element;
        }

    }

    /**
     * An async list using weak references.
     */
    private static final class WeakAsyncList<T> extends AbstractAsyncCollection<WeakReference<T>, T> {

        WeakAsyncList(int initialCapacity) {
            super(initialCapacity);
        }

        @Override
        protected WeakReference<T> wrap(T element) {
            return new WeakReference<>(element);
        }

        @Override
        protected T unwrap(WeakReference<T> element) {
            return element.get();
        }

    }

    private static final class SourceList {

        private final Collection<Source> list = new WeakAsyncList<>(16);
        private boolean complete = false;

        synchronized Collection<Source> getCompleteList() {
            while (!complete) {
                try {
                    wait();
                } catch (InterruptedException ex) {
                    break;
                }
            }
            return list;
        }

        void add(Source source) {
            list.add(source);
        }

        synchronized void notifyComplete() {
            complete = true;
            notifyAll();
        }

        private synchronized boolean addIfIncomplete(Source[] sources) {
            if (!complete) {
                for (Source source : sources) {
                    if (!list.contains(source)) {
                        list.add(source);
                    }
                }
                return true;
            } else {
                return false;
            }
        }
    }
}
