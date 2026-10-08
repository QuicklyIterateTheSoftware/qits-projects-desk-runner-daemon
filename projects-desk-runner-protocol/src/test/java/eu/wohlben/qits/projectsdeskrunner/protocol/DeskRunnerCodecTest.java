package eu.wohlben.qits.projectsdeskrunner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerDecodeException;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The front-desk frames' contract, framework-free: every frame survives {@code encode → decode}
 * through the base codec and this delegate unchanged; each frame's JSON spelling is pinned, key
 * order included; unknown fields are ignored and an unknown type is the base's typed error; and the
 * lists that are desired or observed state refuse to be read as empty when they are absent.
 *
 * <p>The lifecycle frames' byte identity is qits-runner-protocol's and is not repeated here.
 */
class DeskRunnerCodecTest {

  private static final RunnerCodec<DeskRunnerMessage> CODEC =
      new RunnerCodec<>(DeskRunnerProtocol.VOCABULARY, new DeskRunnerCodec());

  private static final String IMAGE = "registry.qits.example.eu/qits/project-agent:2026.1008.1";

  private static final String P1 = "8273c743-e0bb-4d93-90d4-391aa10d9151";
  private static final String P2 = "5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397";

  private static DeskSpec fullSpec() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_PROJECTS_DAEMON_URL", "https://projects.qits.example.eu");
    env.put("TZ", "Europe/Berlin");
    return new DeskSpec(IMAGE, env, "1000", "4g", "8g", 4096L, "2", 600);
  }

  private static DeskSpec bareSpec() {
    return new DeskSpec(IMAGE, Map.of(), null, null, null, null, null, null);
  }

  /** One of every frame, and a second shape where a field is nullable or a list can be empty. */
  private static List<DeskRunnerMessage> everyFrame() {
    return List.of(
        new Estate(
            List.of(
                new DeskEntry(P1, DesiredState.RUNNING, fullSpec(), "sha256:aaa"),
                new DeskEntry(P2, DesiredState.STOPPED, bareSpec(), "sha256:bbb")),
            IMAGE),
        new Estate(List.of(), null),
        new Take(P1, DesiredState.RUNNING, fullSpec(), "sha256:aaa"),
        new Take(P2, DesiredState.STOPPED, bareSpec(), "sha256:bbb"),
        new Remove(P1),
        new ProbeLogin(),
        new HealthCheck("req-1", IMAGE),
        new HealthCheck(null, IMAGE),
        new Inventory(
            List.of(
                new HeldDesk(P1, DeskState.RUNNING, "sha256:aaa", null),
                new HeldDesk(P2, DeskState.FAILED, null, "no space left on device"),
                new HeldDesk("p3", DeskState.STOPPED, "sha256:ccc", null),
                new HeldDesk("p4", DeskState.ABSENT, null, null))),
        new Inventory(List.of()),
        new LaunchFailed(P1, "docker: Error response from daemon: no space left on device"),
        new LaunchFailed(P1, null),
        new Removed(P1),
        new LoginState(LoginPresence.PRESENT),
        new LoginState(LoginPresence.ABSENT),
        new HealthChecked("req-1", true, "ran " + IMAGE + " and saw it running"),
        new HealthChecked(null, false, "it never ran"));
  }

  @Test
  void everyFrameRoundTripsThroughTheBaseCodecAndTheDelegate() {
    for (DeskRunnerMessage frame : everyFrame()) {
      RunnerMessage back = CODEC.decode(CODEC.encode(frame));
      assertEquals(frame, back, () -> "round trip of " + frame);
    }
  }

  @Test
  void theRoundTripCoversEveryPermittedFrame() {
    Set<String> permitted =
        Arrays.stream(DeskRunnerMessage.class.getPermittedSubclasses())
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());
    Set<String> covered =
        everyFrame().stream().map(f -> f.getClass().getSimpleName()).collect(Collectors.toSet());
    assertEquals(permitted, covered, "a frame was added without a round trip");
    assertEquals(
        DeskRunnerProtocol.Type.ALL,
        everyFrame().stream()
            .map(f -> (String) CODEC.encode(f).get("type"))
            .collect(Collectors.toSet()),
        "every wire type is spoken by a frame, and no frame by an undeclared type");
  }

  @Test
  void theBaseCodecAcceptsTheDelegateNoDeskTypeIsALifecycleType() {
    // RunnerCodec refuses a domain type that is a lifecycle type at construction; that this one
    // builds is the assertion. A lifecycle frame still goes through the base, carrying the desk
    // vocabulary.
    Hello hello =
        new Hello(
            DeskRunnerBinary.VERSION,
            DeskRunnerProtocol.CAPABILITY_VERSION,
            8,
            new RunnerCapabilities(true, "amd64", "linux", Map.of()),
            List.of(P1),
            Map.of());
    Map<String, Object> wire = CODEC.encode(hello);
    assertEquals(List.of(P1), wire.get("heldDesks"));
    assertEquals(hello, CODEC.decode(wire));
    Map<String, Object> ack = new LinkedHashMap<>();
    ack.put("type", "ack");
    ack.put("capabilityVersion", 1);
    ack.put("slots", 8);
    ack.put("adoptedDesks", List.of(P1));
    assertEquals(List.of(P1), ((Ack) CODEC.decode(ack)).adopted());
  }

  // --- the golden spelling ---------------------------------------------------------------------

  private static final String FULL_SPEC_JSON =
      "{\"image\":\""
          + IMAGE
          + "\",\"env\":{\"QITS_PROJECTS_DAEMON_URL\":\"https://projects.qits.example.eu\","
          + "\"TZ\":\"Europe/Berlin\"},\"user\":\"1000\",\"memory\":\"4g\",\"memorySwap\":\"8g\","
          + "\"pidsLimit\":4096,\"cpus\":\"2\",\"oomScoreAdj\":600}";

  private static final String BARE_SPEC_JSON =
      "{\"image\":\""
          + IMAGE
          + "\",\"env\":{},\"user\":null,\"memory\":null,\"memorySwap\":null,"
          + "\"pidsLimit\":null,\"cpus\":null,\"oomScoreAdj\":null}";

  @Test
  void eachFrameIsSpelledExactlyAsTheDossierSaysKeyOrderIncluded() {
    assertJson(
        "{\"type\":\"estate\",\"desks\":["
            + "{\"projectId\":\""
            + P1
            + "\",\"desired\":\"RUNNING\",\"spec\":"
            + FULL_SPEC_JSON
            + ",\"specHash\":\"sha256:aaa\"},"
            + "{\"projectId\":\""
            + P2
            + "\",\"desired\":\"STOPPED\",\"spec\":"
            + BARE_SPEC_JSON
            + ",\"specHash\":\"sha256:bbb\"}],"
            + "\"deskImage\":\""
            + IMAGE
            + "\"}",
        new Estate(
            List.of(
                new DeskEntry(P1, DesiredState.RUNNING, fullSpec(), "sha256:aaa"),
                new DeskEntry(P2, DesiredState.STOPPED, bareSpec(), "sha256:bbb")),
            IMAGE));
    assertJson("{\"type\":\"estate\",\"desks\":[],\"deskImage\":null}", new Estate(List.of(), null));
    assertJson(
        "{\"type\":\"take\",\"projectId\":\""
            + P1
            + "\",\"desired\":\"RUNNING\",\"spec\":"
            + FULL_SPEC_JSON
            + ",\"specHash\":\"sha256:aaa\"}",
        new Take(P1, DesiredState.RUNNING, fullSpec(), "sha256:aaa"));
    assertJson("{\"type\":\"remove\",\"projectId\":\"" + P1 + "\"}", new Remove(P1));
    assertJson("{\"type\":\"probeLogin\"}", new ProbeLogin());
    assertJson(
        "{\"type\":\"healthCheck\",\"requestId\":\"req-1\",\"image\":\"" + IMAGE + "\"}",
        new HealthCheck("req-1", IMAGE));
    assertJson(
        "{\"type\":\"inventory\",\"desks\":["
            + "{\"projectId\":\""
            + P1
            + "\",\"state\":\"RUNNING\",\"specHash\":\"sha256:aaa\",\"detail\":null},"
            + "{\"projectId\":\""
            + P2
            + "\",\"state\":\"FAILED\",\"specHash\":null,\"detail\":\"no space\"},"
            + "{\"projectId\":\"p4\",\"state\":\"ABSENT\",\"specHash\":null,\"detail\":null}]}",
        new Inventory(
            List.of(
                new HeldDesk(P1, DeskState.RUNNING, "sha256:aaa", null),
                new HeldDesk(P2, DeskState.FAILED, null, "no space"),
                new HeldDesk("p4", DeskState.ABSENT, null, null))));
    assertJson("{\"type\":\"inventory\",\"desks\":[]}", new Inventory(List.of()));
    assertJson(
        "{\"type\":\"launchFailed\",\"projectId\":\"" + P1 + "\",\"detail\":\"no space\"}",
        new LaunchFailed(P1, "no space"));
    assertJson("{\"type\":\"removed\",\"projectId\":\"" + P1 + "\"}", new Removed(P1));
    assertJson(
        "{\"type\":\"loginState\",\"state\":\"PRESENT\"}", new LoginState(LoginPresence.PRESENT));
    assertJson(
        "{\"type\":\"loginState\",\"state\":\"ABSENT\"}", new LoginState(LoginPresence.ABSENT));
    assertJson(
        "{\"type\":\"healthChecked\",\"requestId\":\"req-1\",\"ok\":true,\"detail\":\"fine\"}",
        new HealthChecked("req-1", true, "fine"));
  }

  private static void assertJson(String expected, DeskRunnerMessage frame) {
    assertEquals(expected, Json.write(CODEC.encode(frame)), () -> "spelling of " + frame);
  }

  // --- tolerance and refusals --------------------------------------------------------------------

  @Test
  void unknownFieldsAreIgnoredAndEitherBoxForANumberIsFine() {
    Map<String, Object> take =
        new LinkedHashMap<>(
            CODEC.encode(new Take(P1, DesiredState.RUNNING, fullSpec(), "sha256:aaa")));
    take.put("priority", 7);
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = new LinkedHashMap<>((Map<String, Object>) take.get("spec"));
    spec.put("pidsLimit", 4096); // an Integer, as Jackson decodes a small number
    spec.put("network", "qits-net"); // a field this version cannot express is not obeyed
    spec.put("hostDockerSocket", true);
    spec.put("mounts", List.of(Map.of("source", "/", "target", "/host")));
    take.put("spec", spec);
    assertEquals(new Take(P1, DesiredState.RUNNING, fullSpec(), "sha256:aaa"), CODEC.decode(take));
  }

  @Test
  void anUnknownTypeIsTheBasesTypedError() {
    RunnerDecodeException e =
        assertThrows(
            RunnerDecodeException.class,
            () -> CODEC.decode(Map.of("type", "migrate", "projectId", P1)));
    assertEquals(RunnerDecodeException.Reason.UNKNOWN_TYPE, e.reason());
  }

  @Test
  void anEstateOrInventoryWithoutItsListIsMalformedNeverEmpty() {
    // Read as empty, an absent `desks` would have the runner remove every desk on its node.
    assertMalformed(Map.of("type", "estate", "deskImage", IMAGE));
    assertMalformed(Map.of("type", "estate", "desks", Map.of(), "deskImage", IMAGE));
    assertMalformed(Map.of("type", "inventory"));
    assertMalformed(Map.of("type", "inventory", "desks", List.of(Map.of("state", "RUNNING"))));
  }

  @Test
  void aMissingProjectDesiredSpecHashOrImageAndAnUnknownStateAreMalformed() {
    Map<String, Object> spec = Map.of("image", IMAGE);
    assertMalformed(Map.of("type", "remove"));
    assertMalformed(Map.of("type", "removed", "projectId", ""));
    assertMalformed(Map.of("type", "launchFailed", "detail", "x"));
    assertMalformed(
        Map.of("type", "take", "desired", "RUNNING", "spec", spec, "specHash", "sha256:a"));
    assertMalformed(Map.of("type", "take", "projectId", P1, "spec", spec, "specHash", "sha256:a"));
    assertMalformed(
        Map.of(
            "type", "take", "projectId", P1, "desired", "PAUSED", "spec", spec, "specHash", "h"));
    assertMalformed(Map.of("type", "take", "projectId", P1, "desired", "RUNNING", "specHash", "h"));
    assertMalformed(Map.of("type", "take", "projectId", P1, "desired", "RUNNING", "spec", spec));
    assertMalformed(
        Map.of(
            "type",
            "take",
            "projectId",
            P1,
            "desired",
            "RUNNING",
            "spec",
            Map.of("env", Map.of()),
            "specHash",
            "h"));
    assertMalformed(
        Map.of(
            "type",
            "estate",
            "desks",
            List.of(Map.of("projectId", P1, "desired", "RUNNING", "spec", spec)),
            "deskImage",
            IMAGE));
    assertMalformed(
        Map.of(
            "type", "inventory", "desks", List.of(Map.of("projectId", P1, "state", "EXPLODED"))));
    assertMalformed(Map.of("type", "loginState", "state", "EXPIRED"));
    assertMalformed(Map.of("type", "loginState"));
  }

  private static void assertMalformed(Map<String, Object> wire) {
    RunnerDecodeException e =
        assertThrows(RunnerDecodeException.class, () -> CODEC.decode(wire), wire::toString);
    assertEquals(RunnerDecodeException.Reason.MALFORMED, e.reason(), wire::toString);
  }

  /**
   * The JSON a map becomes, in its own iteration order — what Jackson and Vert.x both write for
   * these shapes. Test-only and deliberately minimal: strings, numbers, booleans, null, maps, lists.
   */
  static final class Json {

    static String write(Object value) {
      StringBuilder out = new StringBuilder();
      write(value, out);
      return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
      switch (value) {
        case null -> out.append("null");
        case String s -> {
          out.append('"');
          for (char c : s.toCharArray()) {
            switch (c) {
              case '"' -> out.append("\\\"");
              case '\\' -> out.append("\\\\");
              case '\n' -> out.append("\\n");
              default -> out.append(c);
            }
          }
          out.append('"');
        }
        case Number n -> out.append(n);
        case Boolean b -> out.append(b);
        case Map<?, ?> m -> {
          out.append('{');
          boolean first = true;
          for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) {
              out.append(',');
            }
            first = false;
            write(String.valueOf(e.getKey()), out);
            out.append(':');
            write(e.getValue(), out);
          }
          out.append('}');
        }
        case List<?> l -> {
          out.append('[');
          for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
              out.append(',');
            }
            write(l.get(i), out);
          }
          out.append(']');
        }
        default -> throw new IllegalArgumentException("not a wire value: " + value.getClass());
      }
    }
  }
}
