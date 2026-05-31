# `@Expression` DSL reference

`@Expression` selects a target instruction by structural pattern. Pair
it with `@Definition` to alias the JVM signatures the pattern matches
against, and attach it to an `@Inject(at = @At(point = EXPRESSION))`
handler. The runtime parses the expression once at transform time and
matches it against every target instruction.

The DSL is single-instruction: it picks one INVOKE / FIELD /
INSTANCEOF / IF* / arithmetic / CHECKCAST opcode at a time. Sub-expression
shapes (`a.b().c()`, `? + ?`, `(Foo) ?`) are receiver / operand
constraints on that single matched instruction, not multi-instruction
sequences.

## Grammar

```
expression  := assignment
assignment  := logicalOr ("=" arg)?
logicalOr   := logicalAnd ("||" logicalAnd)*
logicalAnd  := comparison ("&&" comparison)*
comparison  := relational | relational "instanceof" IDENT
relational  := additive (relop additive)?
relop       := "==" | "!=" | "<" | "<=" | ">" | ">="
additive    := multiplicative (("+" | "-") multiplicative)*
multiplicative := unary (("*" | "/") unary)*
unary       := "!" unary                 # logical not (comparison only)
              | "(" IDENT ")" unary       # cast
              | primary
primary     := "?"
              | "this"
              | literal
              | "(" expression ")"        # parenthesised sub-expression
              | selector
selector    := ("this" | member) ("." member)*
member      := IDENT ("(" args? ")")?
args        := arg ("," arg)*
arg         := "?" | "this" | IDENT | literal
literal     := INT_LITERAL | STRING_LITERAL | "true" | "false" | "null"
IDENT       := [_A-Za-z][_A-Za-z0-9]*
INT_LITERAL := "-"? DIGIT+
STRING_LITERAL := '"' (any except '"')* '"'
```

Whitespace is permitted between tokens. `true`, `false`, `null`,
`this`, and `instanceof` are reserved keywords — they are recognised
before IDENT.

## Shapes by example

### v1 — single call / field

```java
@Definition(id = "println", method = "java/io/PrintStream.println(Ljava/lang/String;)V")
@Expression("println(?)")
@Inject(method = "run", at = @At(point = At.Point.EXPRESSION))
public void onPrintln(Object self) { ... }
```

```java
@Definition(id = "counter", field = "com/example/Box.counter:I")
@Expression("counter")
@Inject(method = "tick", at = @At(point = At.Point.EXPRESSION))
public void onCounterAccess(Object self) { ... }
```

### v2 — `this`, chained, assignment

```java
@Definition(id = "counter", field = "com/example/Box.counter:I")
@Expression("this.counter = ?")
public void onSet(Object self, int newValue) { ... }
```

```java
@Definition(id = "tick", method = "com/example/Box.tick()V")
@Expression("this.tick()")
public void onTick(Object self) { ... }
```

### v3-A — named captures

```java
@Definition(id = "write", method = "com/example/Writer.write(ILjava/lang/String;)V")
@Expression("write(hi, msg)")
public void onWrite(Object self, String msg, int hi) { ... }
```

Bind-by-name needs `-parameters` on the handler's compile flags. The
project-wide `build.gradle.kts` enables it for every JavaCompile.

### v3-B — multi-segment chains

```java
@Definition(id = "session", method = "com/example/Store.session()Lcom/example/Session;")
@Definition(id = "write", method = "com/example/Session.write(Ljava/lang/String;)V")
@Expression("session().write(?)")
public void onChained(Object self, String msg) { ... }
```

### v3-C — arithmetic

```java
@Expression("? + ?")
public void onAdd(Object self) { ... }
```

Matches `IADD` / `LADD` / `FADD` / `DADD`. v3-C does not bind the
operands; capture-through-arithmetic is v6.

### v4-A — literal args

```java
@Definition(id = "emit", method = "com/example/Bus.emit(I)V")
@Expression("emit(42)")
public void onEmit42(Object self) { ... }
```

Mixed shapes: `emit(?, 42)` binds the first arg and constrains the
second. Supported literals: `42`, `-7`, `"hello"`, `true`, `false`,
`null`.

### v4-B — comparison

```java
@Expression("? == ?")
public void onEq(Object self) { ... }

@Expression("? < ?")
public void onLt(Object self) { ... }
```

Each operator maps to the single opcode javac emits under the
if-condition convention — `if (cond)` jumps when `cond` is false, so
the bytecode carries the negation:

| operator | opcode                    |
|----------|---------------------------|
| `==`     | `IF_ICMPNE` / `IF_ACMPNE` |
| `!=`     | `IF_ICMPEQ` / `IF_ACMPEQ` |
| `<`      | `IF_ICMPGE`               |
| `<=`     | `IF_ICMPGT`               |
| `>`      | `IF_ICMPLE`               |
| `>=`     | `IF_ICMPLT`               |

The pairs `==`/`!=`, `<`/`>=`, `<=`/`>` are therefore distinct.

Jump direction disambiguates the convention: a FORWARD (skip) jump is
an `if`-style negated branch (table above); a BACKWARD jump is a loop
bottom-test using the DIRECT opcode (`while (a < b)` → `IF_ICMPLT`).
The matcher checks the jump's target position and selects the matching
opcode set, so `? < ?` matches both an `if (a < b)` and a
`while (a < b)` site. Ternaries that emit a forward negated jump are
covered; any exotic shape that emits neither a clean forward nor
backward conditional jump is not.

### v7 — `&&` / `||`

```java
@Expression("? < ? && ? > ?")
public void onAnd(Object self, int a, int b, int c, int d) { ... }

@Expression("? == ? || ? != ?")
public void onOr(Object self) { ... }
```

A single `&&` / `||` of two comparisons, matched as a two-jump region
anchored on the first comparison. Captures bind across both operands in
source order. The handler fires before the first comparison's branch.
Nested logical (`a && b && c`), boolean-materialisation context
(`boolean r = a && b;`), and non-comparison operands are not supported.

### v6 — logical not

```java
@Expression("!(? < ?)")
public void onNotLess(Object self) { ... }
```

`!` folds into the negated operator at parse time — `!(? < ?)` is
exactly `? >= ?`. Valid only over a single comparison; to combine
comparisons use the v7 `&&` / `||` region match (it does not compose
with `!` over a logical group).

### v5 — instanceof + cast

```java
@Definition(id = "Str", type = "java/lang/String")
@Expression("? instanceof Str")
public void onInstanceof(Object self) { ... }
```

```java
@Definition(id = "Str", type = "java/lang/String")
@Expression("(Str) ?")
public void onCast(Object self) { ... }
```

`@Definition.type()` is the bytecode-internal name. Exactly one of
`method`, `field`, `type` must be non-empty.

## Capture semantics

- `?` placeholders bind positionally to handler params after
  `Object self`.
- Named arg `id` binds to the handler param with that name (requires
  `-parameters`).
- `this` as an arg binds to `Object self` (slot 0 on the target).
- Literal args constrain — they never bind.

Capture resolution backwalks from the matched instruction to the
producer at the corresponding stack offset. The producer must be a
clean `*LOAD <slot>` for the binding to succeed; non-`*LOAD`
producers throw `InjectSignatureMismatch` so `@Surrogate` can fall
back.

When the handler has no capture params (only `Object self`), every
`?` is treated as inert (v1 backwards-compat).

## Sponge MixinExtras migration cheatsheet

| MixinExtras pattern                | HyperMixins equivalent                                  |
|------------------------------------|---------------------------------------------------------|
| `@Expression("? = ?")`             | `@Expression("this.field = ?")` (with @Definition)      |
| `@Expression("a.b(?)")`            | `@Expression("a.b(?)")` (single-segment unchanged)      |
| `@Expression("this.field")`        | Same                                                    |
| `@Expression("? + ?")`             | Same                                                    |
| `@Expression("instanceof Foo")`    | `@Expression("? instanceof Foo")` (with @Definition.type) |
| `@Expression("(Foo) ?")`           | Same                                                    |

## Out of scope (v8)

- Nested / N-ary logical (`a && b && c`, mixed `&&` / `||`).
- Boolean-materialisation context (`boolean r = a && b;`).
- Logical operands that aren't comparisons (`call() && ? < ?`).
- Backslash escapes in string literals.
- Multi-instruction sequence patterns (`a.b(); c.d()`).
- Long / float / double literals beyond `int`.
- (v6) See list below — capture-through-arithmetic and inner-chain
  captures landed and are no longer deferred.
- IntelliJ syntax highlight for the DSL string.
