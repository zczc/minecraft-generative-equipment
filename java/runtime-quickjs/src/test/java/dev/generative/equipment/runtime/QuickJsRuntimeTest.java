package dev.generative.equipment.runtime;

import static dev.generative.equipment.core.RuntimeTypes.*;
import static dev.generative.equipment.protocol.JsonContracts.MAPPER;
import static org.junit.jupiter.api.Assertions.*;

import dev.generative.equipment.protocol.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuickJsRuntimeTest {
  static final ActivationLease LEASE =
      new ActivationLease(
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          "main_hand",
          1,
          new Contracts.DefinitionRef("test", 1L));

  PreparedDefinition compile(QuickJsRuntime runtime, String code) {
    return runtime.prepare(
        new VerifiedPackage(
            LEASE.definition(), "test", QuickJsRuntime.ENGINE, MAPPER.createObjectNode(), code));
  }

  Invocation input() {
    return new Invocation(
        LEASE,
        new Contracts.RuleEvent(
            "event-test", "item.used", 1L, "internal", MAPPER.createObjectNode()),
        MAPPER.createObjectNode().put("n", 2),
        MAPPER.createObjectNode(),
        MAPPER.createArrayNode());
  }

  ExecutionBudget budget(long ms) {
    return new ExecutionBudget(8 * 1024 * 1024, System.nanoTime() + ms * 1000000, 32, 8);
  }

  @Test
  void runsRealCodeAndResetsGlobals() {
    try (var runtime = new QuickJsRuntime()) {
      var def =
          compile(
              runtime,
              "let n=0; export function onEvent(ctx,e){n++;return {nextState:{n:ctx.state.n+n}}}");
      for (int i = 0; i < 2; i++) {
        var out = runtime.invoke(def, input(), (name, args, lease) -> null, budget(1000));
        assertInstanceOf(InvocationSuccess.class, out);
        assertEquals(3, ((InvocationSuccess) out).decision().nextState().path("n").asInt());
      }
    }
  }

  @Test
  void queryBridgeAndSdkUseOnlyData() {
    try (var r = new QuickJsRuntime()) {
      var d =
          compile(
              r,
              "import {lerp} from '@ge/sdk'; export function onEvent(c){return"
                  + " {nextState:{n:lerp(c.query('actor.status',{}).data.n,10,0.5)}}}");
      var out =
          r.invoke(
              d,
              input(),
              (name, args, lease) ->
                  new QueryResult(true, MAPPER.createObjectNode().put("n", 4), null),
              budget(1000));
      assertInstanceOf(InvocationSuccess.class, out);
      assertEquals(7, ((InvocationSuccess) out).decision().nextState().path("n").asInt());
    }
  }

  @Test
  void stopsLoopsMemoryAndRecursion() {
    try (var r = new QuickJsRuntime()) {
      for (String body :
          new String[] {
            "while(true){}",
            "const a=[];while(true)a.push('x'.repeat(10000))",
            "function f(){f()}f()"
          }) {
        var d = compile(r, "export function onEvent(){" + body + ";return {}}");
        long start = System.nanoTime();
        assertInstanceOf(
            InvocationFailure.class, r.invoke(d, input(), (n, a, l) -> null, budget(30)));
        assertTrue(System.nanoTime() - start < 2_000_000_000L);
      }
    }
  }

  @Test
  void rejectsImportsAndRenderOperations() {
    try (var r = new QuickJsRuntime()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> compile(r, "import x from 'fs'; export function onEvent(){return {}}"));
      var d =
          compile(
              r,
              "export function onEvent(){return {}} export function render(){return"
                  + " {operations:[]}}");
      assertFalse(
          r.render(
                  d,
                  new RenderInvocation(
                      LEASE,
                      MAPPER.createObjectNode(),
                      MAPPER.createObjectNode(),
                      MAPPER.createObjectNode(),
                      1),
                  budget(1000))
              .ok());
    }
  }

  @Test
  void rejectsAsyncInvalidJsonAndQueryFloods() {
    try (var r = new QuickJsRuntime()) {
      for (String code :
          new String[] {
            "export async function onEvent(){return {}}",
            "export function onEvent(){return {nextState:{n:NaN}}}",
            "export function onEvent(){return {nextState:{n:undefined}}}",
            "export function onEvent(c){for(let i=0;i<100;i++)c.query('x',{});return {}}",
            "export function onEvent(){throw new Error('bad')}"
          })
        assertInstanceOf(
            InvocationFailure.class,
            r.invoke(
                compile(r, code),
                input(),
                (n, a, l) -> new QueryResult(true, MAPPER.createObjectNode(), null),
                budget(1000)));
    }
  }

  @Test
  void hasNoHostGlobalsOrClock() {
    try (var r = new QuickJsRuntime()) {
      var d =
          compile(
              r,
              "export function onEvent(){return {nextState:{safe:[typeof process,typeof"
                  + " require,typeof fetch,typeof Date,typeof eval,typeof Function,typeof"
                  + " Math.random,typeof (function(){}).constructor].every(x=>x==='undefined')}}}");
      var out = r.invoke(d, input(), (n, a, l) -> null, budget(1000));
      assertInstanceOf(InvocationSuccess.class, out);
      assertTrue(((InvocationSuccess) out).decision().nextState().path("safe").asBoolean());
    }
  }

  @Test
  void measuresFullInvocationCost() throws Exception {
    try (var r = new QuickJsRuntime()) {
      var d =
          compile(
              r,
              "export function onEvent(c){const q=c.query('actor.status',{});return"
                  + " {nextState:{n:c.state.n+q.data.n},timers:[{action:'set',key:'pulse',afterTicks:3,payload:{}}],operations:[{key:'push',capability:'entity.velocity.add',args:{target:'self',deltaVelocity:{x:0,y:.1,z:0}}}]}}"
                  + " ");
      var query =
          (dev.generative.equipment.core.HostPorts.QueryPort)
              (n, a, l) -> new QueryResult(true, MAPPER.createObjectNode().put("n", 4), null);
      for (int i = 0; i < 50; i++)
        assertInstanceOf(InvocationSuccess.class, r.invoke(d, input(), query, budget(1000)));
      long[] samples = new long[500];
      for (int i = 0; i < samples.length; i++) {
        long start = System.nanoTime();
        assertInstanceOf(InvocationSuccess.class, r.invoke(d, input(), query, budget(1000)));
        samples[i] = System.nanoTime() - start;
      }
      java.util.Arrays.sort(samples);
      var report =
          MAPPER
              .createObjectNode()
              .put("engine", QuickJsRuntime.ENGINE)
              .put("samples", samples.length)
              .put("p50Ms", samples[250] / 1e6)
              .put("p95Ms", samples[475] / 1e6)
              .put("p99Ms", samples[495] / 1e6)
              .put("maxMs", samples[499] / 1e6)
              .put("javaArch", System.getProperty("os.arch"));
      var path = java.nio.file.Path.of("build/reports/runtime-metrics.json");
      java.nio.file.Files.createDirectories(path.getParent());
      java.nio.file.Files.writeString(path, report.toPrettyString());
      System.out.println("Invocation metrics: " + report);
    }
  }
}
