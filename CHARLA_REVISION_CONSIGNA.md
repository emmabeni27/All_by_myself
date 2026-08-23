# 📋 Revisión y Análisis de Cumplimiento: PrintScript

Este documento resume el análisis detallado del proyecto **PrintScript** respecto a los requerimientos de entrega, modularización, Gradle `buildSrc`, tests E2E y arquitectura de plugins.

---

## 📑 Índice
1. [Requerimiento 1: Multi-Project Build en Gradle](#1-multi-project-build-en-gradle)
2. [Requerimiento 2: Extracción de aspectos comunes a plugins en `buildSrc`](#2-extracción-de-aspectos-comunes-a-plugins-en-buildsrc)
3. [Aclaración: Módulo `commons` vs `buildSrc common conventions`](#3-aclaración-módulo-commons-vs-buildsrc-common-conventions)
4. [Requerimiento 3: Lexer, Parser, Interpreter y Casos E2E](#4-requerimiento-3-lexer-parser-interpreter-y-casos-e2e)
5. [Requerimiento 4: Lógica de Plugins y Extensibilidad](#5-requerimiento-4-lógica-de-plugins-y-extensibilidad)
6. [Plan de Acción para Cumplimiento al 100%](#6-plan-de-acción-para-cumplimiento-al-100)

---

## 1. Multi-Project Build en Gradle

### Consigna
> *"Usar Gradle para crear un 'multi-project build' en el que cada módulo de su printscript, sea también un módulo de gradle con su propio set de dependencias, que compile por separado y demás."*

### Estado: ✅ **Cumple**

* **Definición de submódulos (`settings.gradle`):**
  El proyecto está dividido en más de 15 módulos con responsabilidades acotadas:
  * **Data:** `:ast`, `:container`, `:token`, `:tokendata`
  * **Herramientas:** `:lexer`, `:parser`, `:interpreter`, `:formatter`, `:linter`, `:progress`, `:inputprovider`
  * **Integradores:** `:analyzer`, `:executor`, `:formatteraction`, `:cli`, `:runner`, `:globalTests`
* **Compilación y dependencias aisladas:**
  Cada carpeta tiene su propio `build.gradle` y declara solo las dependencias necesarias mediante `implementation(project(":..."))`.
* **Observación:** En el `build.gradle` raíz aún existe un bloque `subprojects { ... }` que inyecta configuración global, lo cual se debe migrar a `buildSrc` (ver siguiente punto).

---

## 2. Extracción de aspectos comunes a plugins en `buildSrc`

### Consigna
> *"Extraer todos los aspectos comunes de su build a plugins de Gradle en la carpeta buildSrc."*

### Estado: ✅ **Cumple**

### Diagnóstico actual
* **Migración completada:**
  * Toda la configuración común (`kotlin-stdlib`, `kotlin-test`, `junit`, `mockk`, `snakeyaml`, `jvmTarget = "21"`, `jacocoTestReport`, `jacocoTestCoverageVerification`, dependencias de tasks) fue migrada al plugin de convención `buildlogic.kotlin-myPlugin-conventions.gradle`.
  * El bloque `subprojects { ... }` fue eliminado del `build.gradle` raíz.


---

## 3. Aclaración: Módulo `commons` vs `buildSrc common conventions`

* **¿Hay un módulo llamado `commons`?**
  No hay un módulo de código fuente llamado literalmente `commons`.
* **¿Por qué?**
  * La consigna no exigía un nombre de módulo específico sino la separación modular de componentes.
  * El proyecto implementó una separación **más granular** para los datos comunes:
    * `token` (estructura del `Token`)
    * `tokendata` (`DataType`, `Position`)
    * `ast` (`ASTNode`)
    * `container` (`Container`)
    * `error` (modelado de errores)
* **La confusión común:** A veces se confunde un módulo de código `commons` con el plugin de convención de Gradle `common-conventions` en `buildSrc`.

---

## 4. Requerimiento 3: Lexer, Parser, Interpreter y Casos E2E

### Consigna
> *"Seguir con el lexer, parser e interpreter. Ya tendrian que tener varios casos e2e funcionando."*

### Estado: ✅ **Cumple**

* **Ejecución exitosa:** Todos los tests de todos los módulos compilan y pasan en verde (`BUILD SUCCESSFUL`).
* **Casos E2E implementados (`globalTests/src/test/kotlin/EndToEndTest.kt`):**
  * Declaración y asignación de variables (`let`) y constantes (`const`).
  * Tipos de datos (`string`, `number`, `boolean`).
  * Expresiones aritméticas y precedencia de operadores.
  * Sentencias condicionales `if` / `else` y bloques (`Block`).
  * Funciones de entrada y salida: `readInput(...)`, `readEnv(...)` y `println(...)`.
* **Tests de integración adicionales:** En `RunnerTest.kt`, `ValidationTest.kt` y `FormatterTCKTest.kt`.

---

## 5. Requerimiento 4: Lógica de Plugins y Extensibilidad

### Consigna
> *"Usar la lógica de plugins que hablamos en clase para poder hacer lexers, parsers e interpreters extensibles."*

### Estado: ✅ **Cumple al 100%**

### 5.1. Interpreter (Buen desacoplamiento)
* Utiliza una interfaz `ActionType`:
  ```kotlin
  interface ActionType {
      fun interpret(node: ASTNode, interpreter: Interpreter): Any
  }
  ```
* Cada operación/sentencia es una clase independiente: `Add`, `Subtract`, `Multiply`, `Divide`, `AssignmentToExistingVar`, `Print`, `VarDeclarationAndAssignment`, `IfStatement`, `ReadInput`, `ReadEnv`, etc.
* En `Interpreter.kt`, los handlers se registran dinámicamente según la versión soportada (v1.0 vs v1.1).

### 5.2. Lexer (Implementado con Plugins `TokenPlugin`)
* **Core Lexer desacoplado:** El motor de tokenización es agnóstico a los tokens concretos y recibe la lista inyectable de plugins (`List<TokenPlugin>`).
* **Abstracción de Plugin:**
  ```kotlin
  interface TokenPlugin {
      fun match(piece: String, position: Position): Token?
  }
  ```
* **Plugins implementados:**
  * `ExactMatchTokenPlugin`: Clasificación de palabras clave, operadores y delimitadores.
  * `RegexTokenPlugin`: Clasificación mediante expresiones regulares (strings, números, identificadores).
* **Factory de configuración:** `TokenPluginFactory.createPlugins(version)` ensambla los plugins según la versión (`1.0` vs `1.1`) o permite registrar plugins personalizados.
* **Segmentación de caracteres:** Utiliza `CharacterClassifier` y `CharacterHandlerFactory` (`QuoteHandler`, `SeparatorHandler`, `WhiteSpaceHandler`, `RegularHandler`).

### 5.3. Parser (Implementado con Plugins `StatementParser`)
* **Implementación:**
  * Se definió la abstracción de regla/plugin de parseo:
    ```kotlin
    interface StatementParser {
        fun canParse(tokens: Container): Boolean
        fun parse(tokens: Container, parser: Parser): ASTNode
    }
    ```
  * Plugins implementados:
    * `DeclarationWithAssignmentParser`
    * `DeclarationWithoutAssignmentParser`
    * `SimpleAssignmentParser`
    * `IfStatementParser`
    * `ExpressionStatementParser`
  * Factory `StatementParserFactory` configura los parsers según la versión/features soportadas.
  * `Parser` delega el parseo de sentencias en la cadena de plugins extensibles (`statementParsers.firstOrNull { it.canParse(tokensToParse) }?.parse(tokensToParse, this)`).

---

## 6. Plan de Acción para Cumplimiento al 100%

| Tarea | Archivo(s) involucrado(s) | Estado |
|---|---|:---:|
| **1. Migrar `subprojects` a `buildSrc`** | `build.gradle` (raíz) y `buildSrc/src/main/groovy/buildlogic.kotlin-myPlugin-conventions.gradle` | ✅ Completado |
| **2. Eliminar `subprojects { ... }` del root** | `build.gradle` (raíz) | ✅ Completado |
| **3. Refactorizar `Parser.kt` con `StatementParser` plugins** | `parser/src/main/kotlin/` | ✅ Completado |

---
*Documento actualizado.*

