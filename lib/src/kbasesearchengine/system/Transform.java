package kbasesearchengine.system;

import java.util.Arrays;
import java.util.List;

import com.google.common.base.Optional;

import kbasesearchengine.common.GUID;
import kbasesearchengine.tools.Utils;

/** Specifies a transform of some data into some other data based on a {@link TransformType}
 * and possibly more specifications depending on the transform type.
 * @author gaprice@lbl.gov
 *
 */
public class Transform {
    
    //TODO TESTS
    
    private static final List<TransformType> SIMPLE_TYPES = Arrays.asList(
            TransformType.integer, TransformType.string, TransformType.values);
    
    private final TransformType type;
    private final Optional<LocationTransformType> location;
    private final Optional<String> targetKey;
    private final Optional<String> targetObjectType;
    // TODO NNOW subobjectIdKey constrains the target type to be a subtype. Add a check in the creation context to check this. 
    
    private final Optional<String> subObjectIdKey;
    
    private Transform(
            final TransformType type,
            final LocationTransformType location,
            final String targetKey,
            final String targetObjectType,
            final String subObjectIdKey) {
        this.type = type;
        this.location = Optional.fromNullable(location);
        this.targetKey = Optional.fromNullable(targetKey);
        this.targetObjectType = Optional.fromNullable(targetObjectType);
        this.subObjectIdKey = Optional.fromNullable(subObjectIdKey);
    }

    /** Get the type of the transform.
     * @return the transform type.
     */
    public TransformType getType() {
        return type;
    }

    /** Return the sub type of the transform for {@link TransformType#location} transforms.
     * For all other transforms this value is absent.
     * @return the location type.
     */
    public Optional<LocationTransformType> getLocation() {
        return location;
    }

    /** Return the key from which data will be extracted for a {@link TransformType#lookup}
     * transforms. For all other transforms this value is absent.
     * @return the key which contains the data of interest in the lookup object.
     */
    public Optional<String> getTargetKey() {
        return targetKey;
    }

    /** Return the type of the object to which the {@link GUID} points for
     * {@link TransformType#guid} transforms. For all other transforms this value is absent.
     * @return the object type to which the guid created by the transform points.
     */
    public Optional<String> getTargetObjectType() {
        return targetObjectType;
    }

    /** Return the key of the object in which the subobject id is stored for
     * {@link TransformType#guid} transforms. The subobject id is used to construct the
     * {@link GUID} in the case where the target object is a subobject in contrast to a parent
     * object, and is not present for any other case.
     * @return
     */
    public Optional<String> getSubobjectIdKey() {
        return subObjectIdKey;
    }
    
    /** Create a {@link TransformType#values} transform.
     * @return the new transform.
     */
    public static Transform values() {
        return new Transform(TransformType.values, null, null, null, null);
    }
    
    /** Create a {@link TransformType#string} transform.
     * @return the new transform.
     */
    public static Transform string() {
        return new Transform(TransformType.string, null, null, null, null);
    }
    
    /** Create a {@link TransformType#integer} transform.
     * @return the new transform.
     */
    public static Transform integer() {
        return new Transform(TransformType.integer, null, null, null, null);
    }
    
    /** Create a {@link TransformType#location} transform.
     * @param location the sub type of the location transform that specifies which portion of the
     * location to extract.
     * @return the new transform.
     */
    public static Transform location(final LocationTransformType location) {
        Utils.nonNull(location, "location");
        return new Transform(TransformType.location, location, null, null, null);
    }
    
    //TODO CODE or DOCUMENTATION what is required for this to work? Does it work with a path?
    /** Create a {@link TransformType#lookup} transform.
     * @param targetKey the key in the target object from which data will be extracted.
     * @return the new transform.
     */
    public static Transform lookup(final String targetKey) {
        Utils.notNullOrEmpty(targetKey, "targetKey cannot be null or empty");
        return new Transform(TransformType.lookup, null, targetKey, null, null);
    }
    
    /** Create a {@link TransformType#guid} transform.
     * @param targetObjectType the type of the object to which the guid is expected to point.
     * @return the new transform.
     */
    public static Transform guid(final String targetObjectType) {
        Utils.notNullOrEmpty(targetObjectType, "targetObjectType cannot be null or empty");
        return new Transform(TransformType.guid, null, null, targetObjectType, null);
    }
    
    /** Create a {@link TransformType#guid} transform.
     * @param targetObjectType the type of the object to which the guid is expected to point.
     * @param subObjectIDKey the key in the current sub object that contains the id of the target
     * sub object (e.g. the sub object within the object that is the target of the GUID). The value
     * of the key will be incorporated into the GUID.
     * @return the new transform.
     */
    public static Transform guid(final String targetObjectType, final String subObjectIDKey) {
        Utils.notNullOrEmpty(targetObjectType, "targetObjectType cannot be null or empty");
        return new Transform(TransformType.guid, null, null, targetObjectType, subObjectIDKey);
    }
    
    /** Create a transform without calling a transform-specific method. This method is often
     * used when creating {@link IndexingRules} for a {@link ObjectTypeParsingRules} from a file.
     * In this case the transform is often specified as 'transform.property' where 'property' is
     * either the location type or the target key for an external object lookup.
     * @param transform the transform type as a string.
     * @param locationOrTargetKey either the {@link LocationTransformType} as a string or
     * the target key for a {@link TransformType#lookup} transform.
     * @param targetObjectType the target object type for a {@link TransformType#guid} transform.
     * @param subObjectIDKey the subobject ID key for a {@link TransformType#guid transform.
     * @return the new transform.
     */
    public static Transform unknown(
            final String transform,
            final String locationOrTargetKey,
            final String targetObjectType,
            final String subObjectIDKey) {
        Utils.notNullOrEmpty(transform, "transform cannot be null or whitespace");
        final TransformType type;
        try {
            type = TransformType.valueOf(transform);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Illegal transform type: " + transform);
        }
        //TODO CODE should this throw an exception if unused fields are specified?
        Utils.nonNull(type, "transform");
        if (SIMPLE_TYPES.contains(type)) {
            return new Transform(type, null, null, null, null);
        }
        if (type.equals(TransformType.location)) {
            Utils.notNullOrEmpty(locationOrTargetKey,
                    "location tranform location type cannot be null or whitespace");
            try {
                return new Transform(type, LocationTransformType.valueOf(locationOrTargetKey),
                        null, null, null);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Illegal tranform location: " +
                        locationOrTargetKey);
            }
        }
        if (type.equals(TransformType.lookup)) {
            Utils.notNullOrEmpty(locationOrTargetKey,
                    "lookup transform target key cannot be null or whitespace");
            return new Transform(type, null, locationOrTargetKey, null, null);
        }
        // ok it's a guid
        Utils.notNullOrEmpty(targetObjectType, "targetObjectType cannot be null or whitespace");
        if (Utils.isNullOrEmpty(subObjectIDKey)) {
            return new Transform(TransformType.guid, null, null, targetObjectType, null);
        } else {
            return new Transform(TransformType.guid, null, null, targetObjectType, subObjectIDKey);
        }
    }

}
