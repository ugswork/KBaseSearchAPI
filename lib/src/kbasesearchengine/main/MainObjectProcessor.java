package kbasesearchengine.main;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonParser;

import kbasesearchengine.AccessFilter;
import kbasesearchengine.GetObjectsInput;
import kbasesearchengine.GetObjectsOutput;
import kbasesearchengine.KeyDescription;
import kbasesearchengine.MatchFilter;
import kbasesearchengine.MatchValue;
import kbasesearchengine.ObjectData;
import kbasesearchengine.Pagination;
import kbasesearchengine.PostProcessing;
import kbasesearchengine.SearchObjectsInput;
import kbasesearchengine.SearchObjectsOutput;
import kbasesearchengine.SearchTypesInput;
import kbasesearchengine.SearchTypesOutput;
import kbasesearchengine.SortingRule;
import kbasesearchengine.TypeDescriptor;
import kbasesearchengine.common.GUID;
import kbasesearchengine.events.AccessGroupProvider;
import kbasesearchengine.events.ObjectStatusEvent;
import kbasesearchengine.events.exceptions.FatalIndexingException;
import kbasesearchengine.events.exceptions.IndexingException;
import kbasesearchengine.events.exceptions.IndexingExceptionUncheckedWrapper;
import kbasesearchengine.events.exceptions.RetriableIndexingException;
import kbasesearchengine.events.handler.EventHandler;
import kbasesearchengine.events.handler.ResolvedReference;
import kbasesearchengine.events.handler.SourceData;
import kbasesearchengine.events.storage.StatusEventStorage;
import kbasesearchengine.parse.KeywordParser;
import kbasesearchengine.parse.ObjectParseException;
import kbasesearchengine.parse.ObjectParser;
import kbasesearchengine.parse.ParsedObject;
import kbasesearchengine.parse.KeywordParser.ObjectLookupProvider;
import kbasesearchengine.queue.ObjectStatusEventIterator;
import kbasesearchengine.queue.ObjectStatusEventQueue;
import kbasesearchengine.search.FoundHits;
import kbasesearchengine.search.IndexingStorage;
import kbasesearchengine.system.IndexingRules;
import kbasesearchengine.system.ObjectTypeParsingRules;
import kbasesearchengine.system.StorageObjectType;
import kbasesearchengine.system.TypeStorage;
import us.kbase.common.service.UObject;
import us.kbase.common.service.UnauthorizedException;

public class MainObjectProcessor {
    
    private static final int RETRY_COUNT = 5;
    private static final int RETRY_SLEEP_MS = 1000;
    
    private final File rootTempDir;
    private final AccessGroupProvider accessGroupProvider;
    private final ObjectStatusEventQueue queue;
    private Thread mainRunner;
    private final TypeStorage typeStorage;
    private final IndexingStorage indexingStorage;
    private final LineLogger logger;
    private final Set<String> admins;
    private final Map<String, EventHandler> eventHandlers = new HashMap<>();
    
    public MainObjectProcessor(
            final AccessGroupProvider accessGroupProvider,
            final List<EventHandler> eventHandlers,
            final StatusEventStorage storage,
            final IndexingStorage indexingStorage,
            final TypeStorage typeStorage,
            final File tempDir,
            final LineLogger logger,
            final Set<String> admins)
            throws IOException, ObjectParseException, UnauthorizedException {
        this.logger = logger;
        this.rootTempDir = tempDir;
        this.admins = admins == null ? Collections.emptySet() : admins;
        
        eventHandlers.stream().forEach(eh -> this.eventHandlers.put(eh.getStorageCode(), eh));
        // 50k simultaneous users * 1000 group ids each seems like plenty = 50M ints in memory
        this.accessGroupProvider = accessGroupProvider;
        queue = new ObjectStatusEventQueue(storage);
        this.typeStorage = typeStorage;
        this.indexingStorage = indexingStorage;
    }
    
    /**
     * For tests only !!!
     */
    public MainObjectProcessor(
            final IndexingStorage indexingStorage,
            final TypeStorage typeStorage,
            final File tempDir,
            final LineLogger logger) 
            throws IOException, ObjectParseException, UnauthorizedException {
        this.accessGroupProvider = null;
        this.queue = null;
        this.rootTempDir = tempDir;
        this.logger = logger;
        this.admins = Collections.emptySet();
        this.typeStorage = typeStorage;
        this.indexingStorage = indexingStorage;
    }
    
    private File getTempSubDir(final String subName) {
        return getTempSubDir(rootTempDir, subName);
    }
    
    public static File getTempSubDir(final File rootTempDir, String subName) {
        File ret = new File(rootTempDir, subName);
        if (!ret.exists()) {
            ret.mkdirs();
        }
        return ret;
    }
    
    public void startLifecycleRunner() {
        if (mainRunner != null) {
            throw new IllegalStateException("Lifecycle runner was already started");
        }
        mainRunner = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while(!Thread.currentThread().isInterrupted()) {
                        try {
                            performOneTick();
                            //TODO ERR log better errors on shutdown
                            //TODO ERR only log unexpected interrupt errors. Set stop flag or something.
                        } catch (InterruptedException e) {
                            logError(e);
                            Thread.currentThread().interrupt();
                        } catch (FatalIndexingException e) {
                            logError(e);
                            Thread.currentThread().interrupt();
                        } catch (Exception e) { //TODO ERR switch to runtime and log
                            logError(e);
                        } finally {
                            if (!Thread.currentThread().isInterrupted()) {
                                try {
                                    Thread.sleep(1000);
                                } catch (InterruptedException e) {
                                    logError(e);
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    }
                } finally {
                    mainRunner = null;
                }
            }
        });
        mainRunner.start();
    }
    
    private void logError(Throwable e) {
        String codePlace = e.getStackTrace().length == 0 ? "<not-available>" : 
            e.getStackTrace()[0].toString();
        if (logger != null) {
            logger.logError("Error in Lifecycle runner: " + e + ", " + codePlace);
            logger.logError(e);
        }
    }
    
    public boolean stopLifecycleRunner() {
        if (mainRunner == null) {
            return false;
        }
        if (mainRunner.isInterrupted()) {
            throw new IllegalStateException("Lifecycle Runner can not be stopped twice");
        }
        mainRunner.interrupt();
        // Let's check every 100 ms during 1 minute at most.
        for (int i = 0; i < 600; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {}
            if (mainRunner == null) {
                return true;
            }
        }
        throw new IllegalStateException("Failed to stop Lifecycle Runner");
    }
    
    //TODO ERR handle the IOExceptions better. These are really data store access exceptions
    private void performOneTick()
            //TODO ERR remove IOException and OPE
            throws IOException, FatalIndexingException, InterruptedException,
                ObjectParseException {
        // Seems like this shouldn't be source specific. It should handle all event sources.
        final ObjectStatusEventIterator iter = queue.iterator("WS");
        while (iter.hasNext()) {
            final ObjectStatusEvent parentEvent = iter.next();
            //TODO LOG log parent event. Add boolean willExpand(event) to eventhandler
            final ExpandResult er = expand(parentEvent);
            if (er.hasException()) {
                handleException("Error expanding parent event", parentEvent, er.exception);
                iter.markAsVisited(false);
            } else {
                iter.markAsVisited(performOneTick(parentEvent, er.event));
            }
        }
    }

    private boolean performOneTick(
            final ObjectStatusEvent parentEvent,
            final Iterator<ObjectStatusEvent> expanditer)
            throws InterruptedException, FatalIndexingException, IOException,
                ObjectParseException {
        while (expanditer.hasNext()) {
            //TODO EVENT insert sub event into db - need to ensure not inserted twice on reprocess - use parent id
            final GetEventResult ger = getNextEvent(parentEvent, expanditer);
            if (ger.hasException()) {
                handleException("Error getting event from data storage",
                        parentEvent, ger.exception);
                return false;
            }
            final ObjectStatusEvent ev = ger.event;
            final StorageObjectType type = ev.getStorageObjectType();
            if (type != null && !isStorageTypeSupported(type)) {
                if (logger != null) {
                    logger.logInfo("[Indexer] skipping " + ev.getEventType() + ", " + 
                            toLogString(type) + ev.toGUID());
                }
                if (ev == parentEvent) { // hack for now. long term insert the sub event into the db
                    return false;
                } else {
                    continue;
                }
            }
            if (logger != null) {
                logger.logInfo("[Indexer] processing " + ev.getEventType() + ", " + 
                        toLogString(type) + ev.toGUID() + "...");
            }
            long time = System.currentTimeMillis();
            final IndexingException ie = processOneEvent(parentEvent, ev);
            if (ie != null) {
                handleException("Error processing event", ev, ie);
                //TODO EVENT set failed on sub event and continue rather than failing completely
                return false;
            } else {
                if (logger != null) {
                    logger.logInfo("[Indexer]   (total time: " +
                            (System.currentTimeMillis() - time) + "ms.)");
                }
            }
        }
        return true;
    }
    
    private IndexingException processOneEvent(
            final ObjectStatusEvent parentEvent,
            final ObjectStatusEvent event)
            //TODO ERR remove IOE and OPE
            throws FatalIndexingException, IOException, ObjectParseException,
                InterruptedException {
        int retries = 1;
        while (true) {
            try {
                processOneEvent(event);
                return null;
            } catch (IndexingException e) {
                if (e instanceof RetriableIndexingException) {
                    if (retries > RETRY_COUNT) {
                        return e;
                    } else {
                        //TODO ERR log better error
                        logError(e);
                        retries++;
                    }
                } else {
                    return e;
                }
            }
            Thread.sleep(RETRY_SLEEP_MS);
        }
    }

    private class ExpandResult {
        public final IndexingException exception;
        public final Iterator<ObjectStatusEvent> event;
        
        public ExpandResult(final Iterator<ObjectStatusEvent> event) {
            this.event = event;
            this.exception = null;
        }
        
        public ExpandResult(final IndexingException exception) {
            this.event = null;
            this.exception = exception;
        }
        
        public boolean hasException() {
            return exception != null;
        }
    }
    
    private ExpandResult expand(final ObjectStatusEvent parentEvent) throws InterruptedException {
        int retries = 0;
        while (true) {
            try {
                return new ExpandResult(getEventHandler(parentEvent)
                        .expand(parentEvent).iterator());
            } catch (IndexingException e) {
                if (e instanceof RetriableIndexingException) {
                    if (retries > RETRY_COUNT) {
                        return new ExpandResult(e);
                    } else {
                        logError(e);
                        //TODO ERR log better error
                        retries++;
                    }
                } else {
                    return new ExpandResult(e);
                }
            }
            Thread.sleep(RETRY_SLEEP_MS);
        }
    }

    private void handleException(
            final String error,
            final ObjectStatusEvent event,
            final IndexingException exception)
            throws FatalIndexingException {
        if (exception instanceof FatalIndexingException) {
            throw (FatalIndexingException) exception;
        } else {
            //TODO NOW better logs. Log which event failed with error.
            logError(exception);
        }
    }

    private class GetEventResult {
        public final IndexingException exception;
        public final ObjectStatusEvent event;
        
        public GetEventResult(final ObjectStatusEvent event) {
            this.event = event;
            this.exception = null;
        }
        
        public GetEventResult(final IndexingException exception) {
            this.event = null;
            this.exception = exception;
        }
        
        public boolean hasException() {
            return exception != null;
        }
    }
    
    private GetEventResult getNextEvent(
            final ObjectStatusEvent parentEvent,
            final Iterator<ObjectStatusEvent> eventiter)
            throws InterruptedException {
        int retries = 1;
        while (true) {
            try {
                return new GetEventResult(eventiter.next());
            } catch (IndexingExceptionUncheckedWrapper wrapped) {
                final IndexingException e = wrapped.getIndexingException();
                if (e instanceof RetriableIndexingException) {
                    if (retries > RETRY_COUNT) {
                        return new GetEventResult(e);
                    } else {
                        logError(e);
                        //TODO ERR log better error
                        retries++;
                    }
                } else {
                    return new GetEventResult(e);
                }
            }
            Thread.sleep(RETRY_SLEEP_MS);
        }
    }

    private String toLogString(final StorageObjectType type) {
        if (type == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(type.getStorageCode());
        sb.append(":");
        sb.append(type.getType());
        if (type.getVersion().isPresent()) {
            sb.append("-");
            sb.append(type.getVersion().get());
        }
        sb.append(", ");
        return sb.toString();
    }

    private EventHandler getEventHandler(final ObjectStatusEvent ev) {
        return getEventHandler(ev.getStorageCode());
    }
    
    private EventHandler getEventHandler(final GUID guid) {
        return getEventHandler(guid.getStorageCode());
    }
    
    private EventHandler getEventHandler(final String storageCode) {
        if (!eventHandlers.containsKey(storageCode)) {
            //TODO EXP need to make this an error such that the event is not reprocessed
            throw new IllegalArgumentException(String.format(
                    "No event handler for storage code %s is registered", storageCode));
        }
        return eventHandlers.get(storageCode);
    }

    public void processOneEvent(ObjectStatusEvent ev) 
            throws IOException, ObjectParseException, IndexingException {
        switch (ev.getEventType()) {
        case NEW_VERSION:
            GUID pguid = ev.toGUID();
            boolean indexed = indexingStorage.checkParentGuidsExist(null, new LinkedHashSet<>(
                    Arrays.asList(pguid))).get(pguid);
            if (indexed) {
                logger.logInfo("[Indexer]   skipping " + pguid + " creation (already indexed)");
                // TODO: we should fix public access for all sub-objects too !!!
                if (ev.isGlobalAccessed()) {
                    publish(pguid);
                } else {
                    unpublish(pguid);
                }
            } else {
                indexObject(pguid, ev.getStorageObjectType(), ev.getTimestamp(),
                        ev.isGlobalAccessed(), null, new LinkedList<>());
            }
            break;
        case DELETED:
            unshare(ev.toGUID(), ev.getAccessGroupId());
            break;
        case DELETE_ALL_VERSIONS:
            unshareAllVersions(ev.toGUID());
            break;
        case UNDELETE_ALL_VERSIONS:
            shareAllVersions(ev.toGUID());
            break;
        case SHARED:
            share(ev.toGUID(), ev.getTargetAccessGroupId());
            break;
        case UNSHARED:
            unshare(ev.toGUID(), ev.getTargetAccessGroupId());
            break;
        case RENAME_ALL_VERSIONS:
            renameAllVersions(ev.toGUID(), ev.getNewName());
            break;
        case PUBLISH_ALL_VERSIONS:
            publishAllVersions(ev.toGUID());
            break;
        case UNPUBLISH_ALL_VERSIONS:
            unpublishAllVersions(ev.toGUID());
            break;
        default:
            throw new IllegalStateException("Unsupported event type: " + ev.getEventType());
        }
    }

    public boolean isStorageTypeSupported(final StorageObjectType storageObjectType)
            throws IOException {
        return !typeStorage.listObjectTypesByStorageObjectType(storageObjectType).isEmpty();
    }
    
    private void indexObject(
            final GUID guid,
            final StorageObjectType storageObjectType,
            Long timestamp,
            final boolean isPublic,
            ObjectLookupProvider indexLookup,
            final List<GUID> objectRefPath) 
            throws IOException, ObjectParseException, IndexingException {
        long t1 = System.currentTimeMillis();
        File tempFile = ObjectParser.prepareTempFile(getTempSubDir(guid.getStorageCode()));
        if (indexLookup == null) {
            indexLookup = new MOPLookupProvider();
        }
        try {
            // make a copy to avoid mutating the caller's path
            final LinkedList<GUID> newRefPath = new LinkedList<>(objectRefPath);
            newRefPath.add(guid);
            final EventHandler handler = getEventHandler(guid);
            final SourceData obj = handler.load(newRefPath, tempFile.toPath());
            if (logger != null) {
                long loadTime = System.currentTimeMillis() - t1;
                logger.logInfo("[Indexer]   " + guid + ", loading time: " + loadTime + " ms.");
                logger.timeStat(guid, loadTime, 0, 0);
            }
            List<ObjectTypeParsingRules> parsingRules = 
                    typeStorage.listObjectTypesByStorageObjectType(storageObjectType);
            for (ObjectTypeParsingRules rule : parsingRules) {
                long t2 = System.currentTimeMillis();
                String parentJson = null;
                try (JsonParser jts = obj.getData().getPlacedStream()) {
                    parentJson = ObjectParser.extractParentFragment(rule, jts);
                }
                Map<GUID, String> guidToJson = ObjectParser.parseSubObjects(obj, guid, rule);
                Map<GUID, ParsedObject> guidToObj = new LinkedHashMap<>();
                for (GUID subGuid : guidToJson.keySet()) {
                    String json = guidToJson.get(subGuid);
                    ParsedObject prsObj = KeywordParser.extractKeywords(rule.getGlobalObjectType(),
                            json, parentJson, rule.getIndexingRules(), indexLookup, newRefPath);
                    guidToObj.put(subGuid, prsObj);
                }
                long parsingTime = System.currentTimeMillis() - t2;
                if (logger != null) {
                    logger.logInfo("[Indexer]   " + rule.getGlobalObjectType() + ", parsing " +
                            "time: " + parsingTime + " ms.");
                }
                long t3 = System.currentTimeMillis();
                if (timestamp == null) {
                    timestamp = System.currentTimeMillis();
                }
                indexingStorage.indexObjects(rule.getGlobalObjectType(), obj,
                        timestamp, parentJson, guid, guidToObj, isPublic, rule.getIndexingRules());
                if (logger != null) {
                    long indexTime = System.currentTimeMillis() - t3;
                    logger.logInfo("[Indexer]   " + rule.getGlobalObjectType() + ", indexing " +
                            "time: " + indexTime + " ms.");
                    logger.timeStat(guid, 0, parsingTime, indexTime);
                }
            }
        } finally {
            tempFile.delete();
        }
    }
    
    public void share(GUID guid, int accessGroupId) throws IOException {
        indexingStorage.shareObjects(new LinkedHashSet<>(Arrays.asList(guid)), accessGroupId, 
                false);
    }
    
    public void shareAllVersions(final GUID guid) throws IOException {
        indexingStorage.undeleteAllVersions(guid);
    }

    public void unshare(GUID guid, int accessGroupId) throws IOException {
        indexingStorage.unshareObjects(new LinkedHashSet<>(Arrays.asList(guid)), accessGroupId);
    }

    public void unshareAllVersions(final GUID guid) throws IOException {
        indexingStorage.deleteAllVersions(guid);
    }

    public void publish(GUID guid) throws IOException {
        indexingStorage.publishObjects(new LinkedHashSet<>(Arrays.asList(guid)));
    }
    
    public void publishAllVersions(final GUID guid) throws IOException {
        indexingStorage.publishAllVersions(guid);
        //TODO DP need to handle objects in datapalette
    }

    public void unpublish(GUID guid) throws IOException {
        indexingStorage.unpublishObjects(new LinkedHashSet<>(Arrays.asList(guid)));
    }
    
    public void unpublishAllVersions(final GUID guid) throws IOException {
        indexingStorage.unpublishAllVersions(guid);
        //TODO DP need to handle objects in datapalette
    }
    
    private void renameAllVersions(final GUID guid, final String newName) throws IOException {
        indexingStorage.setNameOnAllObjectVersions(guid, newName);
    }

    public IndexingStorage getIndexingStorage(String objectType) {
        return indexingStorage;
    }
    
    private static boolean toBool(Long value) {
        return value != null && value == 1L;
    }

    private static boolean toBool(Long value, boolean defaultRet) {
        if (value == null) {
            return defaultRet;
        }
        return value == 1L;
    }

    private static Integer toInteger(Long value) {
        return value == null ? null : (int)(long)value;
    }

    private static GUID toGUID(String value) {
        return value == null ? null : new GUID(value);
    }

    private kbasesearchengine.search.MatchValue toSearch(MatchValue mv, String source) {
        if (mv == null) {
            return null;
        }
        if (mv.getValue() != null) {
            return new kbasesearchengine.search.MatchValue(mv.getValue());
        }
        if (mv.getIntValue() != null) {
            return new kbasesearchengine.search.MatchValue(toInteger(mv.getIntValue()));
        }
        if (mv.getDoubleValue() != null) {
            return new kbasesearchengine.search.MatchValue(mv.getDoubleValue());
        }
        if (mv.getBoolValue() != null) {
            return new kbasesearchengine.search.MatchValue(toBool(mv.getBoolValue()));
        }
        if (mv.getMinInt() != null || mv.getMaxInt() != null) {
            return new kbasesearchengine.search.MatchValue(toInteger(mv.getMinInt()),
                    toInteger(mv.getMaxInt()));
        }
        if (mv.getMinDate() != null || mv.getMaxDate() != null) {
            return new kbasesearchengine.search.MatchValue(mv.getMinDate(), mv.getMaxDate());
        }
        if (mv.getMinDouble() != null || mv.getMaxDouble() != null) {
            return new kbasesearchengine.search.MatchValue(mv.getMinDouble(), mv.getMaxDouble());
        }
        throw new IllegalStateException("Unsupported " + source + " filter: " + mv);
    }
    
    private kbasesearchengine.search.MatchFilter toSearch(MatchFilter mf) {
        kbasesearchengine.search.MatchFilter ret = 
                new kbasesearchengine.search.MatchFilter()
                .withFullTextInAll(mf.getFullTextInAll())
                .withAccessGroupId(toInteger(mf.getAccessGroupId()))
                .withObjectName(mf.getObjectName())
                .withParentGuid(toGUID(mf.getParentGuid()))
                .withTimestamp(toSearch(mf.getTimestamp(), "timestamp"));
        if (mf.getLookupInKeys() != null) {
            Map<String, kbasesearchengine.search.MatchValue> keys =
                    new LinkedHashMap<String, kbasesearchengine.search.MatchValue>();
            for (String key : mf.getLookupInKeys().keySet()) {
                keys.put(key, toSearch(mf.getLookupInKeys().get(key), key));
            }
            ret.withLookupInKeys(keys);
        }
        return ret;
    }

    private kbasesearchengine.search.AccessFilter toSearch(AccessFilter af, String user)
            throws IOException {
        List<Integer> accessGroupIds;
        if (toBool(af.getWithPrivate(), true)) {
            accessGroupIds = accessGroupProvider.findAccessGroupIds(user);
        } else {
            accessGroupIds = Collections.emptyList();
        }
        return new kbasesearchengine.search.AccessFilter()
                .withPublic(toBool(af.getWithPublic()))
                .withAllHistory(toBool(af.getWithAllHistory()))
                .withAccessGroups(new LinkedHashSet<>(accessGroupIds))
                .withAdmin(admins.contains(user));
    }
    
    private kbasesearchengine.search.SortingRule toSearch(SortingRule sr) {
        if (sr == null) {
            return null;
        }
        kbasesearchengine.search.SortingRule ret = new kbasesearchengine.search.SortingRule();
        ret.isTimestamp = toBool(sr.getIsTimestamp());
        ret.isObjectName = toBool(sr.getIsObjectName());
        ret.keyName = sr.getKeyName();
        ret.ascending = !toBool(sr.getDescending());
        return ret;
    }

    private SortingRule fromSearch(kbasesearchengine.search.SortingRule sr) {
        if (sr == null) {
            return null;
        }
        SortingRule ret = new SortingRule();
        if (sr.isTimestamp) {
            ret.withIsTimestamp(1L);
        } else if (sr.isObjectName) {
            ret.withIsObjectName(1L);
        } else {
            ret.withKeyName(sr.keyName);
        }
        ret.withDescending(sr.ascending ? 0L : 1L);
        return ret;
    }

    private kbasesearchengine.search.Pagination toSearch(Pagination pg) {
        return pg == null ? null : new kbasesearchengine.search.Pagination(
                toInteger(pg.getStart()), toInteger(pg.getCount()));
    }

    private Pagination fromSearch(kbasesearchengine.search.Pagination pg) {
        return pg == null ? null : new Pagination().withStart((long)pg.start)
                .withCount((long)pg.count);
    }

    private kbasesearchengine.search.PostProcessing toSearch(PostProcessing pp) {
        kbasesearchengine.search.PostProcessing ret = 
                new kbasesearchengine.search.PostProcessing();
        if (pp == null) {
            ret.objectInfo = true;
            ret.objectData = true;
            ret.objectKeys = true;
        } else {
            boolean idsOnly = toBool(pp.getIdsOnly());
            ret.objectInfo = !(toBool(pp.getSkipInfo()) || idsOnly);
            ret.objectData = !(toBool(pp.getSkipData()) || idsOnly);
            ret.objectKeys = !(toBool(pp.getSkipKeys()) || idsOnly);
        }
        return ret;
    }
    
    private kbasesearchengine.ObjectData fromSearch(
            final kbasesearchengine.search.ObjectData od) {
        final kbasesearchengine.ObjectData ret = new kbasesearchengine.ObjectData();
        ret.withGuid(od.guid.toString());
        ret.withObjectProps(new HashMap<>());
        if (od.parentGuid != null) {
            ret.withParentGuid(od.parentGuid.toString());
        }
        if (od.timestamp > 0) {
            ret.withTimestamp(od.timestamp);
        }
        if (od.data != null) {
            ret.withData(new UObject(od.data));
        }
        if (od.parentData != null) {
            ret.withParentData(new UObject(od.parentData));
        }
        ret.withObjectName(od.objectName);
        ret.withKeyProps(od.keyProps);
        addObjectProp(ret, od.creator, "creator");
        addObjectProp(ret, od.copier, "copied");
        addObjectProp(ret, od.module, "module");
        addObjectProp(ret, od.method, "method");
        addObjectProp(ret, od.moduleVersion, "module_ver");
        addObjectProp(ret, od.commitHash, "commmit");
        return ret;
    }
    
    private void addObjectProp(final ObjectData ret, final String prop, final String propkey) {
        if (prop != null) {
            ret.getObjectProps().put(propkey, prop);
        }
    }

    public SearchTypesOutput searchTypes(SearchTypesInput params, String user) throws Exception {
        long t1 = System.currentTimeMillis();
        kbasesearchengine.search.MatchFilter matchFilter = toSearch(params.getMatchFilter());
        kbasesearchengine.search.AccessFilter accessFilter = toSearch(params.getAccessFilter(),
                user);
        Map<String, Integer> ret = indexingStorage.searchTypes(matchFilter, accessFilter);
        return new SearchTypesOutput().withTypeToCount(ret.keySet().stream().collect(
                Collectors.toMap(Function.identity(), c -> (long)(int)ret.get(c))))
                .withSearchTime(System.currentTimeMillis() - t1);
    }
    
    public SearchObjectsOutput searchObjects(SearchObjectsInput params, String user) 
            throws Exception {
        long t1 = System.currentTimeMillis();
        kbasesearchengine.search.MatchFilter matchFilter = toSearch(params.getMatchFilter());
        List<kbasesearchengine.search.SortingRule> sorting = null;
        if (params.getSortingRules() != null) {
            sorting = params.getSortingRules().stream().map(this::toSearch).collect(
                    Collectors.toList());
        }
        kbasesearchengine.search.AccessFilter accessFilter = toSearch(params.getAccessFilter(),
                user);
        kbasesearchengine.search.Pagination pagination = toSearch(params.getPagination());
        kbasesearchengine.search.PostProcessing postProcessing = 
                toSearch(params.getPostProcessing());
        FoundHits hits = indexingStorage.searchObjects(params.getObjectType(), matchFilter, 
                sorting, accessFilter, pagination, postProcessing);
        SearchObjectsOutput ret = new SearchObjectsOutput();
        ret.withPagination(fromSearch(hits.pagination));
        ret.withSortingRules(hits.sortingRules.stream().map(this::fromSearch).collect(
                Collectors.toList()));
        if (hits.objects == null) {
            ret.withObjects(hits.guids.stream().map(guid -> new kbasesearchengine.ObjectData().
                    withGuid(guid.toString())).collect(Collectors.toList()));
        } else {
            ret.withObjects(hits.objects.stream().map(this::fromSearch).collect(
                    Collectors.toList()));
        }
        ret.withTotal((long)hits.total);
        ret.withSearchTime(System.currentTimeMillis() - t1);
        return ret;
    }

    public GetObjectsOutput getObjects(final GetObjectsInput params, final String user)
            throws Exception {
        final long t1 = System.currentTimeMillis();
        final Set<Integer> accessGroupIDs =
                new HashSet<>(accessGroupProvider.findAccessGroupIds(user));
        final Set<GUID> guids = new LinkedHashSet<>();
        for (final String guid : params.getGuids()) {
            final GUID g = new GUID(guid);
            //TODO DP this is a quick fix for now, doesn't take data palettes into account
            if (accessGroupIDs.contains(g.getAccessGroupId())) {
                // don't throw an error, just don't return data
                guids.add(g);
            }
        }
        final kbasesearchengine.search.PostProcessing postProcessing = 
                toSearch(params.getPostProcessing());
        final List<kbasesearchengine.search.ObjectData> objs = indexingStorage.getObjectsByIds(
                guids, postProcessing);
        final GetObjectsOutput ret = new GetObjectsOutput().withObjects(objs.stream()
                .map(this::fromSearch).collect(Collectors.toList()));
        ret.withSearchTime(System.currentTimeMillis() - t1);
        return ret;
    }
    
    public Map<String, TypeDescriptor> listTypes(String uniqueType) throws Exception {
        Map<String, TypeDescriptor> ret = new LinkedHashMap<>();
        for (ObjectTypeParsingRules otpr : typeStorage.listObjectTypes()) {
            String typeName = otpr.getGlobalObjectType();
            if (uniqueType != null && !uniqueType.equals(typeName)) {
                continue;
            }
            String uiTypeName = otpr.getUiTypeName();
            if (uiTypeName == null) {
                uiTypeName = guessUIName(typeName);
            }
            List<KeyDescription> keys = new ArrayList<>();
            for (IndexingRules ir : otpr.getIndexingRules()) {
                if (ir.isNotIndexed()) {
                    continue;
                }
                String keyName = KeywordParser.getKeyName(ir);
                String uiKeyName = ir.getUiName();
                if (uiKeyName == null) {
                    uiKeyName = guessUIName(keyName);
                }
                String keyValueType = ir.getKeywordType();
                if (keyValueType == null) {
                    keyValueType = "string";
                }
                long hidden = ir.isUiHidden() ? 1L : 0L;
                KeyDescription kd = new KeyDescription().withKeyName(keyName)
                        .withKeyUiTitle(uiKeyName).withKeyValueType(keyValueType)
                        .withKeyValueType(keyValueType).withHidden(hidden)
                        .withLinkKey(ir.getUiLinkKey());
                keys.add(kd);
            }
            TypeDescriptor td = new TypeDescriptor().withTypeName(typeName)
                    .withTypeUiTitle(uiTypeName).withKeys(keys);
            ret.put(typeName, td);
        }
        return ret;
    }

    private static String guessUIName(String id) {
        return id.substring(0, 1).toUpperCase() + id.substring(1);
    }
    
    private class MOPLookupProvider implements ObjectLookupProvider {
        private Map<String, String> refResolvingCache = new LinkedHashMap<>();
        private Map<GUID, kbasesearchengine.search.ObjectData> objLookupCache =
                new LinkedHashMap<>();
        private Map<GUID, String> guidToTypeCache = new LinkedHashMap<>();
        
        @Override
        public Set<String> resolveWorkspaceRefs(List<GUID> callerRefPath, Set<String> refs)
                throws IOException {
            /* the caller ref path 1) ensures that the object refs are valid when checked against
             * the source, and 2) allows getting deleted objects with incoming references 
             * in the case of the workspace
             */
            
            // there may be a way to cache more of this info and call the workspace less
            // by checking the ref against the refs in the parent object.
            // doing it the dumb way for now.
            final EventHandler eh = getEventHandler(callerRefPath.get(0));
            final Map<String, String> refToRefPath = eh.buildReferencePaths(callerRefPath, refs);
            Set<String> ret = new LinkedHashSet<>();
            Set<String> refsToResolve = new LinkedHashSet<>();
            for (final String ref : refs) {
                final String refpath = refToRefPath.get(ref);
                if (refResolvingCache.containsKey(refpath)) {
                    ret.add(refResolvingCache.get(refpath));
                } else {
                    refsToResolve.add(ref);
                }
            }
            if (refsToResolve.size() > 0) {
                try {
                    final Set<ResolvedReference> resrefs =
                            eh.resolveReferences(callerRefPath, refsToResolve);
                    for (final ResolvedReference rr: resrefs) {
                        final GUID guid = rr.getResolvedReferenceAsGUID();
                        boolean indexed = indexingStorage.checkParentGuidsExist(null, 
                                new LinkedHashSet<>(Arrays.asList(guid))).get(guid);
                        if (!indexed) {
                            indexObject(guid, rr.getType(), rr.getTimestamp(), false,
                                    this, callerRefPath);
                        }
                        ret.add(rr.getResolvedReference());
                        refResolvingCache.put(refToRefPath.get(rr.getReference()),
                                rr.getResolvedReference());
                    }
                } catch (IOException e) {
                    throw e;
                } catch (Exception ex) {
                    throw new IOException(ex);
                }
            }
            return ret;
        }
        
        @Override
        public Map<GUID, kbasesearchengine.search.ObjectData> lookupObjectsByGuid(
                Set<GUID> guids) throws IOException {
            Map<GUID, kbasesearchengine.search.ObjectData> ret = new LinkedHashMap<>();
            Set<GUID> guidsToLoad = new LinkedHashSet<>();
            for (GUID guid : guids) {
                if (objLookupCache.containsKey(guid)) {
                    ret.put(guid, objLookupCache.get(guid));
                } else {
                    guidsToLoad.add(guid);
                }
            }
            if (guidsToLoad.size() > 0) {
                kbasesearchengine.search.PostProcessing pp = 
                        new kbasesearchengine.search.PostProcessing();
                pp.objectData = false;
                pp.objectKeys = true;
                pp.objectInfo = true;
                List<kbasesearchengine.search.ObjectData> objList = 
                        indexingStorage.getObjectsByIds(guidsToLoad, pp);
                Map<GUID, kbasesearchengine.search.ObjectData> loaded = 
                        objList.stream().collect(Collectors.toMap(od -> od.guid, 
                                Function.identity()));
                objLookupCache.putAll(loaded);
                ret.putAll(loaded);
            }
            return ret;
        }
        
        @Override
        public ObjectTypeParsingRules getTypeDescriptor(String type) {
            try {
                return typeStorage.getObjectType(type);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
        
        @Override
        public Map<GUID, String> getTypesForGuids(Set<GUID> guids) throws IOException {
            Map<GUID, String> ret = new LinkedHashMap<>();
            Set<GUID> guidsToLoad = new LinkedHashSet<>();
            for (GUID guid : guids) {
                if (guidToTypeCache.containsKey(guid)) {
                    ret.put(guid, guidToTypeCache.get(guid));
                } else {
                    guidsToLoad.add(guid);
                }
            }
            if (guidsToLoad.size() > 0) {
                kbasesearchengine.search.PostProcessing pp = 
                        new kbasesearchengine.search.PostProcessing();
                pp.objectData = false;
                pp.objectKeys = false;
                pp.objectInfo = true;
                Map<GUID, String> loaded = indexingStorage.getObjectsByIds(guids, pp).stream()
                        .collect(Collectors.toMap(od -> od.guid, od -> od.type));
                guidToTypeCache.putAll(loaded);
                ret.putAll(loaded);
            }
            return ret;
        }
    }
}