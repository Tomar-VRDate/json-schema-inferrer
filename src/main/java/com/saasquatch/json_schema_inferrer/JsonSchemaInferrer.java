package com.saasquatch.json_schema_inferrer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import org.apache.commons.validator.routines.EmailValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.apache.commons.validator.routines.UrlValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.node.ValueNode;
import com.github.fge.jsonpatch.diff.JsonDiff;
import com.google.common.collect.ImmutableSet;

/**
 * Infer JSON schema based on a sample JSON
 *
 * @author sli
 * @see #newBuilder()
 * @see #infer(JsonNode)
 */
@Immutable
public final class JsonSchemaInferrer {

  private final Draft draft;
  private final boolean includeDollarSchema;
  private final boolean inferFormat;
  private final boolean includeDefault;
  private final boolean includeExamples;

  JsonSchemaInferrer(@Nonnull Draft draft, boolean includeDollarSchema, boolean inferFormat,
      boolean includeDefault, boolean includeExamples) {
    this.draft = draft;
    this.includeDollarSchema = includeDollarSchema;
    this.inferFormat = inferFormat;
    this.includeDefault = includeDefault;
    this.includeExamples = includeExamples;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * @param input the sample JSON
   * @return the inferred JSON schema
   */
  public ObjectNode infer(@Nullable JsonNode input) {
    final ObjectNode result = newObject();
    if (includeDollarSchema) {
      result.put(Fields.DOLLAR_SCHEMA, draft.url);
    }
    if (input instanceof ObjectNode) {
      result.setAll(processObject((ObjectNode) input));
    } else if (input instanceof ArrayNode) {
      result.setAll(processArray((ArrayNode) input));
    } else {
      // input is null or a ValueNode
      result.setAll(processPrimitive((ValueNode) input));
    }
    return result;
  }

  @Nullable
  private String inferFormat(@Nullable JsonNode value) {
    if (!inferFormat || value == null) {
      return null;
    }
    if (value.textValue() != null) {
      final String textValue = value.textValue();
      try {
        Instant.parse(textValue);
        return "date-time";
      } catch (Exception e) {
        // Ignore
      }
      if (draft.sameOrNewerThan(Draft.V7)) {
        try {
          LocalTime.parse(textValue);
          return "time";
        } catch (Exception e) {
          // Ignore
        }
        try {
          LocalDate.parse(textValue);
          return "date";
        } catch (Exception e) {
          // Ignore
        }
      }
      if (EmailValidator.getInstance().isValid(textValue)) {
        return "email";
      }
      if (InetAddressValidator.getInstance().isValidInet4Address(textValue)) {
        return "ipv4";
      }
      if (InetAddressValidator.getInstance().isValidInet6Address(textValue)) {
        return "ipv6";
      }
      if (UrlValidator.getInstance().isValid(textValue)) {
        return "uri";
      }
    }
    return null;
  }

  @Nonnull
  private static String inferType(@Nullable JsonNode value) {
    if (value == null) {
      return Types.NULL;
    }
    final JsonNodeType type = value.getNodeType();
    switch (type) {
      case ARRAY:
        return Types.ARRAY;
      case BINARY:
        return Types.STRING;
      case BOOLEAN:
        return Types.BOOLEAN;
      case MISSING:
        return Types.NULL;
      case NULL:
        return Types.NULL;
      case NUMBER:
        return value.isIntegralNumber() ? Types.INTEGER : Types.NUMBER;
      case OBJECT:
        return Types.OBJECT;
      case POJO:
        throw new IllegalArgumentException(POJONode.class.getSimpleName() + " not supported");
      case STRING:
        return Types.STRING;
      default:
        break;
    }
    throw new IllegalArgumentException(
        String.format(Locale.ROOT, "Unrecognized %s: %s", type.getClass().getSimpleName(), type));
  }

  private ObjectNode processPrimitive(@Nullable ValueNode valueNode) {
    final ObjectNode result = newObject();
    result.put(Fields.TYPE, inferType(valueNode));
    final String format = inferFormat(valueNode);
    if (format != null) {
      result.put(Fields.FORMAT, format);
    }
    if (includeDefault) {
      result.set(Fields.DEFAULT, valueNode);
    }
    if (includeExamples) {
      final ArrayNode examples = newArray().add(valueNode);
      result.set(Fields.EXAMPLES, examples);
    }
    return result;
  }

  private ObjectNode processObject(@Nonnull ObjectNode objectNode) {
    final ObjectNode properties = newObject();
    final Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
    while (fields.hasNext()) {
      final Map.Entry<String, JsonNode> field = fields.next();
      final String key = field.getKey();
      final JsonNode val = field.getValue();
      if (val instanceof ObjectNode) {
        properties.set(key, processObject((ObjectNode) val));
      } else if (val instanceof ArrayNode) {
        properties.set(key, processArray((ArrayNode) val));
      } else {
        properties.set(key, processPrimitive((ValueNode) val));
      }
    }
    final ObjectNode result = newObject().put(Fields.TYPE, Types.OBJECT);
    if (properties.size() > 0) {
      result.set(Fields.PROPERTIES, properties);
    }
    return result;
  }

  private ObjectNode processArray(@Nonnull ArrayNode arrayNode) {
    final ObjectNode items;
    final Set<ObjectNode> oneOfs = new HashSet<>();
    for (JsonNode val : arrayNode) {
      if (val instanceof ObjectNode) {
//        oneOfs.add(processObject((ObjectNode) val));
        addOneOf(oneOfs, processObject((ObjectNode) val));
      } else if (val instanceof ArrayNode) {
//        oneOfs.add(processArray((ArrayNode) val));
        addOneOf(oneOfs, processArray((ArrayNode) val));
      } else {
//        oneOfs.add(processPrimitive((ValueNode) val));
        addOneOf(oneOfs, processPrimitive((ValueNode) val));
      }
    }
    processOneOfs(oneOfs);
    switch (oneOfs.size()) {
      case 0:
        items = newObject();
        break;
      case 1:
        items = oneOfs.iterator().next();
        break;
      default: {
        items = newObject();
        final ArrayNode oneOfArray = newArray();
        oneOfs.forEach(oneOfArray::add);
        items.set(Fields.ONE_OF, oneOfArray);
      }
    }
    final ObjectNode result = newObject().put(Fields.TYPE, Types.ARRAY);
    result.set(Fields.ITEMS, items);
    return result;
  }

  private void addOneOf(Set<ObjectNode> oneOfs, ObjectNode newOneOf) {
    if (oneOfs.isEmpty()) {
      oneOfs.add(newOneOf);
      return;
    }
    final Iterator<ObjectNode> oneOfIter = oneOfs.iterator();
    while (oneOfIter.hasNext()) {
      final ObjectNode oneOf = oneOfIter.next();
      final JsonNode diff = JsonDiff.asJson(oneOf, newOneOf);
      final Set<String> ops = StreamSupport.stream(diff.spliterator(), false)
          .map(j -> j.path("op").textValue())
          .filter(Objects::nonNull)
          .collect(Collectors.toSet());
      if (ops.equals(ImmutableSet.of("add"))) {
        oneOfIter.remove();
        break;
      } else if (ops.equals(ImmutableSet.of("remove"))) {
        // The new oneOf is a subset of one of the existing oneOfs
        // Do nothing
        return;
      }
    }
    oneOfs.add(newOneOf);
  }

  public static void main(String[] args) {
    ObjectNode j1 = newObject().put("a", 1);
    ObjectNode j2 = newObject().put("b", 2).put("a", "b");
    System.out.println(JsonDiff.asJson(j2, j1));
  }

  private void processOneOfs(Set<ObjectNode> oneOfs) {
    final Set<String> types = oneOfs.stream()
        .map(j -> j.path(Fields.TYPE).textValue())
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    if (types.contains(Types.INTEGER) && types.contains(Types.NUMBER)) {
      oneOfs.removeIf(j -> Types.INTEGER.equals(j.path(Fields.TYPE).textValue()));
    }
  }

  public static final class Builder {

    private Draft draft = Draft.V4;
    private boolean includeDollarSchema = true;
    private boolean inferFormat = true;
    private boolean includeDefault;
    private boolean includeExamples;

    private Builder() {}

    /**
     * Set the draft version to draft-04. The default is draft-04.
     */
    public Builder draft04() {
      return withDraft(Draft.V4);
    }

    /**
     * Set the draft version to draft-06. The default is draft-04.
     */
    public Builder draft06() {
      return withDraft(Draft.V6);
    }

    /**
     * Set the draft version to draft-07. The default is draft-04.
     */
    public Builder draft07() {
      return withDraft(Draft.V7);
    }

    private Builder withDraft(@Nonnull Draft draft) {
      this.draft = Objects.requireNonNull(draft);
      return this;
    }

    /**
     * Set whether {@code $schema} should be included in the output. It is true by default.
     */
    public Builder includeDollarSchema(boolean includeDollarSchema) {
      this.includeDollarSchema = includeDollarSchema;
      return this;
    }

    /**
     * Set whether we should infer the {@code format} of the input, i.e. email, ipv4, ipv6, etc. It
     * is true by default.
     */
    public Builder inferFormat(boolean inferFormat) {
      this.inferFormat = inferFormat;
      return this;
    }

    /**
     * Set whether {@code default} should be included in the output. The values will be the same as
     * the input. It is false by default.
     */
    public Builder includeDefault(boolean includeDefault) {
      this.includeDefault = includeDefault;
      return this;
    }


    /**
     * Set whether {@code examples} should be included in the output. The values will be the same as
     * the input. It is false by default.
     */
    public Builder includeExamples(boolean includeExamples) {
      this.includeExamples = includeExamples;
      return this;
    }

    /**
     * @return the {@link JsonSchemaInferrer} built
     * @throws IllegalArgumentException if the draft version and features don't match up
     */
    public JsonSchemaInferrer build() {
      if (!draft.sameOrNewerThan(Draft.V6) && includeExamples) {
        throw new IllegalArgumentException(
            String.format(Locale.ROOT, "Draft version[%s] does not support examples", draft.url));
      }
      return new JsonSchemaInferrer(draft, includeDollarSchema, inferFormat, includeDefault,
          includeExamples);
    }

  }

  private static enum Draft {

    V4("http://json-schema.org/draft-04/schema#"),
    V6("http://json-schema.org/draft-06/schema#"),
    V7("http://json-schema.org/draft-07/schema#"),
    ;

    @Nonnull
    private final String url;

    Draft(String url) {
      this.url = url;
    }

    public boolean sameOrNewerThan(Draft other) {
      return this.compareTo(other) >= 0;
    }

  }

  private static interface Fields {
    String TYPE = "type", ITEMS = "items", ONE_OF = "oneOf", /* REQUIRED = "required", */
        PROPERTIES = "properties", FORMAT = "format", DEFAULT = "default", EXAMPLES = "examples",
        DOLLAR_SCHEMA = "$schema"/* , TITLE = "title" */;
  }

  private static interface Types {
    String OBJECT = "object", ARRAY = "array", STRING = "string", BOOLEAN = "boolean",
        INTEGER = "integer", NUMBER = "number", NULL = "null";
  }

  private static ObjectNode newObject() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static ArrayNode newArray() {
    return JsonNodeFactory.instance.arrayNode();
  }

}
