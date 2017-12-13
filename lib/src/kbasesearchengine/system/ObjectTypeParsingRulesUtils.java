package kbasesearchengine.system;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import kbasesearchengine.common.ObjectJsonPath;
import kbasesearchengine.parse.ObjectParseException;
import kbasesearchengine.system.ObjectTypeParsingRules.Builder;
import kbasesearchengine.tools.Utils;

/** Utilities for creating {@link ObjectTypeParsingRules} from various data sources.
 * 
 * This class is not thread-safe.
 * @author gaprice@lbl.gov
 *
 */
public class ObjectTypeParsingRulesUtils {

    //TODO TEST

    /** Create an ObjectTypeParsingRules instance from a file.
     * 
     * TODO document the file structure.
     * @param file the file containing the parsing rules.
     * @return a new set of parsing rules.
     * @throws IOException if an IO error occurs reading the file.
     * @throws TypeParseException if the file contains erroneous parsing rules.
     */
    public static ObjectTypeParsingRules fromFile(final File file) 
            throws IOException, TypeParseException {
        try (final InputStream is = new FileInputStream(file)) {
            return fromStream(is, file.toString());
        }
    }

    private static ObjectTypeParsingRules fromStream(InputStream is, String sourceInfo) 
            throws IOException, TypeParseException {
        final Yaml yaml = new Yaml(new SafeConstructor());
        final Object predata;
        try {
            predata = yaml.load(is);
        } catch (Exception e) {
            // wtf snakeyaml authors, not using checked exceptions is bad enough, but not
            // documenting any exceptions and overriding toString so you can't tell what
            // exception is being thrown is something else
            throw new TypeParseException(String.format("Error parsing source %s: %s %s",
                    sourceInfo, e.getClass(), e.getMessage()), e);
        }
        if (!(predata instanceof Map)) {
            throw new TypeParseException(
                    "Expected mapping in top level YAML/JSON." + sourceInfo);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) predata;
        return fromObject(obj, sourceInfo);
    }

    private static ObjectTypeParsingRules fromObject(
            final Map<String, Object> obj,
            final String sourceInfo) 
            throws TypeParseException {
        try {
            final String storageCode = (String)obj.get("storage-type");
            final String type = (String)obj.get("storage-object-type");
            if (Utils.isNullOrEmpty(storageCode)) {
                throw new ObjectParseException(getMissingKeyParseMessage("storage-type"));
            }
            if (Utils.isNullOrEmpty(type)) {
                throw new ObjectParseException(getMissingKeyParseMessage("storage-object-type"));
            }
            final Builder builder = ObjectTypeParsingRules.getBuilder(
                    (String) obj.get("global-object-type"), //TODO CODE better error if missing
                    new StorageObjectType(storageCode, type))
                    .withNullableUITypeName((String)obj.get("ui-type-name"));
            final String subType = (String)obj.get("inner-sub-type");
            if (!Utils.isNullOrEmpty(subType)) {
                builder.toSubObjectRule(
                        subType,
                        //TODO CODE add checks to ensure these exist
                        getPath((String)obj.get("path-to-sub-objects")),
                        getPath((String)obj.get("primary-key-path")));
            } // throw exception if the other subobj values exist?
            // Indexing
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> indexingRules =
                    (List<Map<String, Object>>)obj.get("indexing-rules");
            if (indexingRules != null) {
                for (Map<String, Object> rulesObj : indexingRules) {
                    builder.withIndexingRule(buildRule(rulesObj));
                }
            }
            return builder.build();
        } catch (ObjectParseException | IllegalArgumentException | NullPointerException e) {
            throw new TypeParseException(String.format("Error in source %s: %s",
                    sourceInfo, e.getMessage()), e);
        }
    }

    private static IndexingRules buildRule(
            final Map<String, Object> rulesObj)
            throws ObjectParseException {
        final String path = (String) rulesObj.get("path");
        final String keyName = (String) rulesObj.get("key-name");
        final IndexingRules.Builder irBuilder;
        if (Utils.isNullOrEmpty(path)) {
            final String sourceKey = (String)rulesObj.get("source-key");
            irBuilder = IndexingRules.fromSourceKey(sourceKey, keyName);
        } else {
            //TODO CODE throw exception if sourceKey != null?
            irBuilder = IndexingRules.fromPath(new ObjectJsonPath(path));
            if (!Utils.isNullOrEmpty(keyName)) {
                irBuilder.withKeyName(keyName);
            }
        }
        if (getBool((Boolean) rulesObj.get("from-parent"))) {
            irBuilder.withFromParent();
        }
        if (getBool(rulesObj.get("full-text"))) {
            irBuilder.withFullText();
        }
        final String keywordType = (String)rulesObj.get("keyword-type");
        if (!Utils.isNullOrEmpty(keywordType)) {
            //TODO CODE throw an error if fullText is true?
            irBuilder.withKeywordType(keywordType);
        }
        final String transform = (String) rulesObj.get("transform");
        if (!Utils.isNullOrEmpty(transform)) {
            final String subObjectIDKey = (String) rulesObj.get("subobject-id-key");
            final String targetObjectType =
                    (String) rulesObj.get("target-object-type");
            final String[] tranSplt = transform.split("\\.", 2);
            final String transProp = tranSplt.length == 1 ? null : tranSplt[1];
            irBuilder.withTransform(Transform.unknown(
                    tranSplt[0], transProp, targetObjectType, subObjectIDKey));
        }
        if (getBool(rulesObj.get("not-indexed"))) {
            irBuilder.withNotIndexed();
        }
        irBuilder.withNullableDefaultValue(rulesObj.get("optional-default-value"));
        irBuilder.withNullableUIName((String) rulesObj.get("ui-name"));
        if (getBool(rulesObj.get("ui-hidden"))) {
            irBuilder.withUIHidden();
        }
        irBuilder.withNullableUILinkKey((String) rulesObj.get("ui-link-key"));
        return irBuilder.build();
    }
    
    private static boolean getBool(final Object putativeBool) {
        //TODO CODE precheck cast exception
        return putativeBool != null && (Boolean) putativeBool; 
    }
    
    private static String getMissingKeyParseMessage(final String key) {
        return String.format("Missing key %s", key);
    }

    private static ObjectJsonPath getPath(String path) throws ObjectParseException {
        return path == null ? null : new ObjectJsonPath(path);
    }
    
}