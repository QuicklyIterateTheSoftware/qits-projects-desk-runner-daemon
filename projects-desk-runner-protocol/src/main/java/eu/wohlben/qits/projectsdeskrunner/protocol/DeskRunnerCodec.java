package eu.wohlben.qits.projectsdeskrunner.protocol;

import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol.Field;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol.Type;
import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.WireMaps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The front-desk frames over {@code Map<String,Object>}: the {@link DomainCodec}
 * qits-runner-protocol's {@code RunnerCodec} delegates every non-lifecycle {@code type} to. Both
 * peers use it — the runner bridging the map with a Vert.x {@code JsonObject}, qits-projects with
 * Jackson — so a field is spelled in exactly one place.
 *
 * <p>Build it into a socket codec as {@code new RunnerCodec<>(DeskRunnerProtocol.VOCABULARY, new
 * DeskRunnerCodec())}.
 *
 * <p><b>Tolerant where it is safe, strict where it is not.</b> Every field is read through {@link
 * WireMaps}, so unknown fields are ignored and either box for a number is fine. A missing project
 * id, desired state, spec, spec hash or spec image is a refusal — {@code RunnerCodec} reports it as
 * MALFORMED and the receiver drops the frame — and so is an enum value this version does not know,
 * and a missing {@code desks} on an {@link Estate} or an {@link Inventory}: those lists are desired
 * and observed state, and an absence read as "empty" would remove every desk on a node.
 *
 * <p>Every frame writes {@code type} first and its fields in record order; a nullable field is
 * written as {@code null} rather than left out, as {@code WorkspacesRunnerCodec}'s spec is.
 */
public final class DeskRunnerCodec implements DomainCodec<DeskRunnerMessage> {

  @Override
  public Class<DeskRunnerMessage> messageType() {
    return DeskRunnerMessage.class;
  }

  @Override
  public Set<String> types() {
    return Type.ALL;
  }

  @Override
  public Map<String, Object> encode(DeskRunnerMessage message) {
    Map<String, Object> map = new LinkedHashMap<>();
    switch (message) {
      case Estate m -> {
        map.put(Field.TYPE, Type.ESTATE);
        List<Object> desks = new ArrayList<>();
        for (DeskEntry entry : m.desks()) {
          Map<String, Object> desk = new LinkedHashMap<>();
          entry(desk, entry.projectId(), entry.desired(), entry.spec(), entry.specHash());
          desks.add(desk);
        }
        map.put(Field.DESKS, desks);
        map.put(Field.DESK_IMAGE, m.deskImage());
      }
      case Take m -> {
        map.put(Field.TYPE, Type.TAKE);
        entry(map, m.projectId(), m.desired(), m.spec(), m.specHash());
      }
      case Remove m -> {
        map.put(Field.TYPE, Type.REMOVE);
        map.put(Field.PROJECT_ID, m.projectId());
      }
      case ProbeLogin _ -> map.put(Field.TYPE, Type.PROBE_LOGIN);
      case HealthCheck m -> {
        map.put(Field.TYPE, Type.HEALTH_CHECK);
        map.put(Field.REQUEST_ID, m.requestId());
        map.put(Field.IMAGE, m.image());
      }
      case Inventory m -> {
        map.put(Field.TYPE, Type.INVENTORY);
        List<Object> desks = new ArrayList<>();
        for (HeldDesk held : m.desks()) {
          Map<String, Object> desk = new LinkedHashMap<>();
          desk.put(Field.PROJECT_ID, held.projectId());
          desk.put(Field.STATE, held.state().name());
          desk.put(Field.SPEC_HASH, held.specHash());
          desk.put(Field.DETAIL, held.detail());
          desks.add(desk);
        }
        map.put(Field.DESKS, desks);
      }
      case LaunchFailed m -> {
        map.put(Field.TYPE, Type.LAUNCH_FAILED);
        map.put(Field.PROJECT_ID, m.projectId());
        map.put(Field.DETAIL, m.detail());
      }
      case Removed m -> {
        map.put(Field.TYPE, Type.REMOVED);
        map.put(Field.PROJECT_ID, m.projectId());
      }
      case LoginState m -> {
        map.put(Field.TYPE, Type.LOGIN_STATE);
        map.put(Field.STATE, m.state().name());
      }
      case HealthChecked m -> {
        map.put(Field.TYPE, Type.HEALTH_CHECKED);
        map.put(Field.REQUEST_ID, m.requestId());
        map.put(Field.OK, m.ok());
        map.put(Field.DETAIL, m.detail());
      }
    }
    return map;
  }

  @Override
  public DeskRunnerMessage decode(String type, Map<String, Object> map) {
    return switch (type) {
      case Type.ESTATE -> new Estate(entries(map), WireMaps.str(map, Field.DESK_IMAGE));
      case Type.TAKE -> {
        DeskEntry entry = entry(map);
        yield new Take(entry.projectId(), entry.desired(), entry.spec(), entry.specHash());
      }
      case Type.REMOVE -> new Remove(projectId(map));
      case Type.PROBE_LOGIN -> new ProbeLogin();
      case Type.HEALTH_CHECK ->
          new HealthCheck(WireMaps.str(map, Field.REQUEST_ID), WireMaps.str(map, Field.IMAGE));
      case Type.INVENTORY -> new Inventory(held(map));
      case Type.LAUNCH_FAILED -> new LaunchFailed(projectId(map), WireMaps.str(map, Field.DETAIL));
      case Type.REMOVED -> new Removed(projectId(map));
      case Type.LOGIN_STATE ->
          new LoginState(constant(LoginPresence.class, WireMaps.str(map, Field.STATE), Field.STATE));
      case Type.HEALTH_CHECKED ->
          new HealthChecked(
              WireMaps.str(map, Field.REQUEST_ID),
              WireMaps.boolVal(map, Field.OK),
              WireMaps.str(map, Field.DETAIL));
      default -> throw new IllegalArgumentException("not a front-desk runner frame: " + type);
    };
  }

  // --- a desk entry: an estate's element and a take's body ---------------------------------------

  private static void entry(
      Map<String, Object> map,
      String projectId,
      DesiredState desired,
      DeskSpec spec,
      String specHash) {
    map.put(Field.PROJECT_ID, projectId);
    map.put(Field.DESIRED, desired == null ? null : desired.name());
    map.put(Field.SPEC, spec == null ? null : spec(spec));
    map.put(Field.SPEC_HASH, specHash);
  }

  private static DeskEntry entry(Map<String, Object> map) {
    String projectId = projectId(map);
    DesiredState desired =
        constant(DesiredState.class, WireMaps.str(map, Field.DESIRED), Field.DESIRED);
    DeskSpec spec = spec(WireMaps.object(map, Field.SPEC));
    String specHash = WireMaps.str(map, Field.SPEC_HASH);
    if (specHash == null || specHash.isBlank()) {
      throw new IllegalArgumentException(Field.SPEC_HASH + " is missing");
    }
    return new DeskEntry(projectId, desired, spec, specHash);
  }

  private static List<DeskEntry> entries(Map<String, Object> map) {
    List<DeskEntry> entries = new ArrayList<>();
    for (Map<String, Object> desk : objects(map, Field.DESKS)) {
      entries.add(entry(desk));
    }
    return entries;
  }

  private static List<HeldDesk> held(Map<String, Object> map) {
    List<HeldDesk> held = new ArrayList<>();
    for (Map<String, Object> desk : objects(map, Field.DESKS)) {
      held.add(
          new HeldDesk(
              projectId(desk),
              constant(DeskState.class, WireMaps.str(desk, Field.STATE), Field.STATE),
              WireMaps.str(desk, Field.SPEC_HASH),
              WireMaps.str(desk, Field.DETAIL)));
    }
    return held;
  }

  // --- the spec ----------------------------------------------------------------------------------

  private static Map<String, Object> spec(DeskSpec s) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(Field.IMAGE, s.image());
    map.put(Field.ENV, new LinkedHashMap<>(s.env()));
    map.put(Field.USER, s.user());
    map.put(Field.MEMORY, s.memory());
    map.put(Field.MEMORY_SWAP, s.memorySwap());
    map.put(Field.PIDS_LIMIT, s.pidsLimit());
    map.put(Field.CPUS, s.cpus());
    map.put(Field.OOM_SCORE_ADJ, s.oomScoreAdj());
    return map;
  }

  private static DeskSpec spec(Map<String, Object> map) {
    if (map == null) {
      throw new IllegalArgumentException(Field.SPEC + " is missing");
    }
    Number pids = WireMaps.number(map, Field.PIDS_LIMIT);
    Number oom = WireMaps.number(map, Field.OOM_SCORE_ADJ);
    return new DeskSpec(
        WireMaps.str(map, Field.IMAGE),
        WireMaps.stringMap(map, Field.ENV),
        WireMaps.str(map, Field.USER),
        WireMaps.str(map, Field.MEMORY),
        WireMaps.str(map, Field.MEMORY_SWAP),
        pids == null ? null : pids.longValue(),
        WireMaps.str(map, Field.CPUS),
        oom == null ? null : oom.intValue());
  }

  // --- readers -----------------------------------------------------------------------------------

  /** The {@code projectId} field: required, a non-blank string. */
  private static String projectId(Map<String, Object> map) {
    String value = WireMaps.str(map, Field.PROJECT_ID);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(Field.PROJECT_ID + " is missing");
    }
    return value;
  }

  /** An enum spelled as its constant's name; absent or unknown is a refusal, never a guess. */
  private static <E extends Enum<E>> E constant(Class<E> type, String value, String key) {
    if (value == null) {
      throw new IllegalArgumentException(key + " is missing");
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("unknown " + key + ": " + value);
    }
  }

  /** A required list of nested objects, each a {@code Map} or a Vert.x {@code JsonObject}. */
  private static List<Map<String, Object>> objects(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      throw new IllegalArgumentException(key + " is missing");
    }
    if (!(value instanceof Iterable<?> items) || value instanceof Map<?, ?>) {
      throw new IllegalArgumentException("'" + key + "' is not a list");
    }
    List<Map<String, Object>> objects = new ArrayList<>();
    for (Object item : items) {
      // WireMaps reads a nested object out of a map by key; wrapping the item reuses that reading,
      // so a JsonObject inside a JsonArray is accepted exactly as a nested one is.
      Map<String, Object> holder = new LinkedHashMap<>();
      holder.put(key, item);
      Map<String, Object> object = WireMaps.object(holder, key);
      if (object == null) {
        throw new IllegalArgumentException("'" + key + "' holds a null");
      }
      objects.add(object);
    }
    return objects;
  }
}
