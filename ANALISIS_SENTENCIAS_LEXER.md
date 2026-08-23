# 🧠 Análisis Arquitectónico: ¿Tiene ventajas que el Lexer forme la sentencia al final?

Este documento analiza en detalle las ventajas, desventajas, implicaciones de diseño y alternativas arquitectónicas de que el **Lexer** agrupe y entregue tokens a nivel de **sentencia** (`lexIntoStatements(): Sequence<Container>`) en lugar de emitir un flujo continuo e indiferenciado de tokens.

---

## 📌 Contexto en PrintScript

En la implementación actual del proyecto, el Lexer provee dos niveles de abstracción:
1. `split(): Sequence<String>`: Tokenización a bajo nivel carácter por carácter / lexema por lexema.
2. `lexIntoStatements(): Sequence<Container>`: Reconocimiento de los delimitadores de sentencia (`;` para instrucciones simples, `{ ... }` y `else` con balanceo de llaves para bloques `if/else`) para retornar cada sentencia como un `Container` de tokens independientes.

El pipeline (`Executor`, `Analyzer`) consume este flujo de la siguiente forma:
```kotlin
val statements = lexer.lexIntoStatements()
for (statement in statements) {
    val parser = Parser(statement, version)
    val ast = parser.parse()
    interpreter.interpret(ast) // o analyzer.validate(ast)
}
```

---

## 🚀 Ventajas de formar la sentencia en el Lexer

### 1. 🌊 Streaming y Eficiencia de Memoria (Bajo Footprint)
* **Consumo bajo demanda (Lazy Evaluation):** Al utilizar `Sequence<Container>`, el archivo fuente no se carga por completo en memoria en forma de un gigantesco AST.
* **Procesamiento de archivos gigantes:** Se puede procesar un script de millones de líneas consumiendo memoria proporcional al tamaño de la sentencia más larga, descartando los tokens y nodos AST de sentencias ya ejecutadas/validadas.

### 2. ⚡ Ejecución e Interpretación Incremental (Paso a Paso / REPL)
* **Feedback inmediato:** Permite que un intérprete interactivo (CLI / REPL) o un runner ejecute una sentencia apenas termina de escribirse/leerse.
* **Efectos de lado progresivos:** Las llamadas como `println(...)` o `readInput(...)` ocurren en el orden natural en que se van procesando las sentencias sin necesidad de parsear previamente todo el archivo.

### 3. 🧩 Simplificación Drástica del `Parser`
* **Parser focalizado:** El `Parser` solo necesita saber cómo construir el árbol de **una única sentencia o expresión** a la vez (`Parser(statement)`), en vez de lidiar con una gramática superior de programa completo (`Program -> List<Statement>`).
* **Lógica modular de reglas:** Facilita la aplicación del patrón de plugins (`StatementParser`), ya que cada regla solo compite por evaluar un bloque atómico y acotado de tokens.

### 4. 🛡️ Aislamiento y Recuperación de Errores
* **Límites claros de error:** Si una sentencia contiene un error de sintaxis o tipo, el parser o linter falla de manera localizada en esa sentencia sin desincronizar la lectura léxica de las sentencias subsiguientes.
* **Diagnósticos precisos:** Los reportes de error pueden asociarse directamente al número de sentencia / rango de tokens específico.

### 5. 📊 Facilidad de Medición de Progreso (`progress`)
* Al trabajar con un iterador de sentencias, los módulos como `progress` o barras de avance de compilación/análisis pueden cuantificar fácilmente el número de sentencias procesadas frente al total.

---

## ⚖️ Desventajas y Trade-offs a Considerar

A pesar de sus ventajas prácticas, este enfoque introduce compromisos de diseño que deben balancearse:

| Aspecto | Desafío | Impacto en PrintScript |
|---|---|---|
| **Separación de Responsabilidades (SoC)** | Teóricamente, el Lexer solo debe reconocer *tokens* (léxico), mientras que la estructura y delimitación de *sentencias* pertenece a la gramática sintáctica (*Parser*). | El Lexer asume conocimiento de la estructura sintáctica (`{`, `}`, `else`, `braceDepth`). |
| **Complejidad del Lexer ante estructuras avanzadas** | Si el lenguaje crece con funciones, clases, lambdas anidadas o template literals multilínea, el Lexer necesita un mecanismo de lookahead cada vez más complejo (`PeekingIterator`). | Requiere inspeccionar espacios en blanco y tokens posteriores tras una llave de cierre `}` para no cortar un `if` antes de su `else`. |
| **Estado Mutable Temporal** | Requiere buffers acumuladores temporales (`currentStatementStrings: MutableList<String>`) mientras se delimita la sentencia. | Debe gestionarse cuidadosamente para evitar pérdidas de tokens o problemas de concurrencia. |

---

## 📊 Tabla Comparativa de Enfoques

| Criterio | Flujo Continuo de Tokens (Parser monolítico) | Sentencias formadas en Lexer (`lexIntoStatements`) | Token Stream + Adaptador/Splitter Intermedio |
|---|:---:|:---:|:---:|
| **Pureza Arquitectónica (SoC)** | ⭐⭐⭐⭐⭐ (Alta) | ⭐⭐⭐ (Media) | ⭐⭐⭐⭐⭐ (Alta) |
| **Simplicidad del Parser** | ⭐⭐ (Complejo) | ⭐⭐⭐⭐⭐ (Muy simple) | ⭐⭐⭐⭐ (Simple) |
| **Uso de Memoria (Streaming)** | ⭐⭐ (Carga todo el AST) | ⭐⭐⭐⭐⭐ (Lazy por sentencia) | ⭐⭐⭐⭐⭐ (Lazy por sentencia) |
| **Extensibilidad de Reglas** | ⭐⭐⭐ (Gramática rígida) | ⭐⭐⭐⭐⭐ (Plugins por sentencia) | ⭐⭐⭐⭐⭐ (Plugins por sentencia) |
| **Facilidad para CLI / REPL** | ⭐⭐ (Requiere buffer manual) | ⭐⭐⭐⭐⭐ (Nativo) | ⭐⭐⭐⭐⭐ (Nativo) |

---

## 🎯 Conclusión y Recomendación

1. **¿Tiene ventajas?**
   **Sí, rotundas en el contexto de PrintScript.** Permite mantener el `Parser` ligero y desacoplado, habilita el procesamiento en streaming de archivos grandes y facilita la ejecución sentencia a sentencia en runners, CLI y análisis estático.

2. **Mejora arquitectónica sugerida (si se busca 100% de pureza conceptual):**
   Si en futuras versiones se desea desacoplar totalmente al `Lexer` del conocimiento de sentencias, se puede extraer la lógica de `lexIntoStatements()` a un componente intermedio (`StatementSplitter` / `StatementStreamer` o `LexerAdapter`) que reciba `Sequence<Token>` del Lexer puro y emita `Sequence<Container>` hacia el Parser.
