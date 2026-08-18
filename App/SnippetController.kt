package com.grupo14IngSis.snippetSearcherApp.controller

import com.grupo14IngSis.snippetSearcherApp.client.AccessManagerClient
import com.grupo14IngSis.snippetSearcherApp.client.RunnerClient
import com.grupo14IngSis.snippetSearcherApp.domain.Snippet
import com.grupo14IngSis.snippetSearcherApp.domain.Test
import com.grupo14IngSis.snippetSearcherApp.domain.UserData
import com.grupo14IngSis.snippetSearcherApp.dto.CancelExecutionRequest
import com.grupo14IngSis.snippetSearcherApp.dto.CreateTestRequest
import com.grupo14IngSis.snippetSearcherApp.dto.CreateTestResponse
import com.grupo14IngSis.snippetSearcherApp.dto.GetPermissionsForUserResponse
import com.grupo14IngSis.snippetSearcherApp.dto.InputSendRequest
import com.grupo14IngSis.snippetSearcherApp.dto.RunTestResponse
import com.grupo14IngSis.snippetSearcherApp.dto.ShareSnippetRequest
import com.grupo14IngSis.snippetSearcherApp.dto.SnippetCreationRequest
import com.grupo14IngSis.snippetSearcherApp.dto.SnippetCreationResponse
import com.grupo14IngSis.snippetSearcherApp.dto.SnippetData
import com.grupo14IngSis.snippetSearcherApp.dto.SnippetPermissionData
import com.grupo14IngSis.snippetSearcherApp.dto.SnippetRunRequest
import com.grupo14IngSis.snippetSearcherApp.dto.SnippetUpdateRequest
import com.grupo14IngSis.snippetSearcherApp.dto.StartExecutionResponse
import com.grupo14IngSis.snippetSearcherApp.repository.SnippetRepository
import com.grupo14IngSis.snippetSearcherApp.repository.TestRepository
import com.grupo14IngSis.snippetSearcherApp.repository.UserDataRepository
import com.grupo14IngSis.snippetSearcherApp.service.SnippetTaskProducer
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class SnippetController(
    private val accessManagerClient: AccessManagerClient,
    private val runnerClient: RunnerClient,
    private val snippetRepository: SnippetRepository,
    private val testRepository: TestRepository,
    private val userDataRepository: UserDataRepository,
    private val snippetTaskProducer: SnippetTaskProducer,
    private val redisTemplate: RedisTemplate<String, String>,
    @Value("\${redis.stream.key}") private val streamKey: String,
) {
    private val logger = LoggerFactory.getLogger(SnippetController::class.java)
    private val ownerPermission = 2
    private val sharedPermission = 1
    private val noPermission = 0

    private fun getAuthorization(
        userId: String,
        snippetId: String,
    ): Int {
        val permission = accessManagerClient.getPermission(userId, snippetId) ?: return noPermission
        return when {
            permission.role.lowercase() == "owner" -> ownerPermission
            permission.role.lowercase() == "shared" -> sharedPermission
            else -> noPermission
        }
    }

    /**
     * GET /api/v1/snippets
     *
     * Get all snippets available for a user
     *
     * Response: a map containing the id of each snippet associated with the snippet permission data
     *
     *     {
     *         snippet1: {
     *             name: String,
     *             language: String,
     *             permission: owner
     *         },
     *         snippet2: {
     *             name: String,
     *             language: String,
     *             permission: shared
     *         },
     *         ...
     *     }
     *     */
    @GetMapping("/snippets")
    @PreAuthorize("isAuthenticated()")
    fun getAllSnippets(authentication: Authentication): ResponseEntity<Map<String, SnippetPermissionData>> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        val snippetPermissions: GetPermissionsForUserResponse =
            accessManagerClient.getPermissionsForUser(userId) ?: return ResponseEntity.status(404).build()
        val output = mutableMapOf<String, SnippetPermissionData>()
        snippetPermissions.owned.forEach {
            val snippet = snippetRepository.findById(it).get()
            output[it] = SnippetPermissionData(snippet.name, snippet.language, "owner")
        }
        snippetPermissions.shared.forEach {
            var snippet = snippetRepository.findById(it).orElse(null)
            if (snippet == null) {
                val snippetData = runnerClient.getSnippetData(it)
                if (snippetData != null) {
                    snippet = Snippet(snippetData.snippetId, snippetData.name, snippetData.language, it)
                    snippetRepository.save(snippet)
                }
            }
            if (snippet != null) {
                output[it] = SnippetPermissionData(snippet.name, snippet.language, "shared")
            }
        }
        return ResponseEntity.ok().body(output)
    }

    /**
     * GET    /api/v1/snippets/{snippetId}
     *
     * Get metadata associated with the snippet
     *
     * Response:
     *
     *     {
     *         snippetId: String,
     *         name: String,
     *         language: String
     *     }
     */
    @GetMapping("/snippets/{snippetId}")
    fun getSnippetData(
        @PathVariable snippetId: String,
    ): ResponseEntity<SnippetData> {
        val snippet = snippetRepository.findById(snippetId).get()
        val response = SnippetData(snippet.snippetId, snippet.name, snippet.language)
        return ResponseEntity.ok().body(response)
    }

    /**
     * PUT    /api/v1/snippets/{snippetId}
     *
     * Register a snippet into App's database. It also adds owner permission to the current user.
     *
     * This endpoint is meant to be used by Runner after creating a snippet, using the same JWT used for the creation request
     *
     * Request:
     *
     *     {
     *         userId: String,
     *         name: String, -> Snippet name
     *         language: String
     *     }
     *
     * Response:
     *
     *     {
     *       success: Boolean,
     *       message: String
     *     }
     */
    @PutMapping("/snippets/{snippetId}")
    fun registerSnippet(
        @PathVariable snippetId: String,
        @RequestBody request: SnippetCreationRequest,
    ): ResponseEntity<SnippetCreationResponse> {
        try {
            snippetRepository.save(
                Snippet(
                    snippetId,
                    request.name,
                    request.language,
                    snippetId,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            return ResponseEntity.badRequest().body(
                SnippetCreationResponse(
                    false,
                    "Snippet already exists",
                ),
            )
        } catch (e: Exception) {
            return ResponseEntity.status(500).body(
                SnippetCreationResponse(
                    false,
                    "Error creating snippet: ${e.message}",
                ),
            )
        }
        accessManagerClient.postPermission(request.userId, snippetId, "owner")
        return ResponseEntity.ok().body(
            SnippetCreationResponse(
                true,
                "Snippet with ID $snippetId created successfully",
            ),
        )
    }

    /**
     * DELETE /api/v1/snippets/{snippetId}
     *
     * Delete a snippet
     */
    @DeleteMapping("/snippets/{snippetId}")
    @PreAuthorize("isAuthenticated()")
    fun deleteSnippet(
        authentication: Authentication,
        @PathVariable snippetId: String,
    ): ResponseEntity<Any> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        if (getAuthorization(userId, snippetId) < ownerPermission) {
            return ResponseEntity.status(401).build()
        }
        accessManagerClient.deletePermissionForSnippet(snippetId)
        runnerClient.deleteSnippet("snippets", snippetId)
        snippetRepository.deleteById(snippetId)
        testRepository.deleteBySnippetId(snippetId)
        return ResponseEntity.ok().build()
    }

    /**
     * GET    /api/v1/snippets/{snippetId}/permission
     *
     * Get all users with permission for a snippet
     *
     * Request:
     *
     *     {
     *       userId: name...
     *       userId2: name,
     *       ...
     *     }
     */
    @GetMapping("/snippets/{snippetId}/permission")
    @PreAuthorize("isAuthenticated()")
    fun getUsersWithPermission(
        authentication: Authentication,
        @PathVariable snippetId: String,
    ): ResponseEntity<List<Map<String, String>>> {
        val jwt = authentication.principal as Jwt
        val ownerId = jwt.subject
        if (getAuthorization(ownerId, snippetId) < ownerPermission) {
            return ResponseEntity.status(401).build()
        }
        val users =
            accessManagerClient.getPermissionsForSnippet(snippetId)
                ?: return ResponseEntity.notFound().build()
        val shared = users.shared
        val userList = mutableListOf<Map<String, String>>()
        for (user in shared) {
            val userData = userDataRepository.findById(user).orElse(UserData(user, user))
            userList.add(mapOf("id" to userData.userId, "email" to userData.userName))
        }
        return ResponseEntity.ok(userList)
    }

    /**
     * PUT    /api/v1/snippets/{snippetId}/permission
     *
     * Share a snippet with another user
     *
     * Request:
     *
     *     {
     *       userId: {userId}
     *     }
     */
    @PutMapping("/snippets/{snippetId}/permission")
    @PreAuthorize("isAuthenticated()")
    fun shareSnippet(
        authentication: Authentication,
        @PathVariable snippetId: String,
        @RequestBody snippetData: ShareSnippetRequest,
    ): ResponseEntity<Any> {
        val jwt = authentication.principal as Jwt
        val ownerId = jwt.subject
        if (getAuthorization(ownerId, snippetId) < ownerPermission) {
            return ResponseEntity.status(401).build()
        }

        // Traduce el email al Auth0 ID si existe en la BD, o usa el ID directo
        val targetUserId = userDataRepository.findByUserNameIgnoreCase(snippetData.userId)?.userId
            ?: snippetData.userId

        try {
            accessManagerClient.postPermission(targetUserId, snippetId, "shared")
        } catch (e: HttpClientErrorException.BadRequest) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("User already has permission for this snippet.")
        }
        return ResponseEntity.ok().build()
    }

    /**
     * DELETE /api/v1/snippets/{snippetId}/permission/{userId}
     *
     * Remove permission for another user
     */
    @DeleteMapping("/snippets/{snippetId}/permission/{userId}")
    @PreAuthorize("isAuthenticated()")
    fun removeSnippetPermission(
        authentication: Authentication,
        @PathVariable snippetId: String,
        @PathVariable userId: String,
    ): ResponseEntity<Any> {
        val jwt = authentication.principal as Jwt
        val ownerId = jwt.subject
        if (getAuthorization(ownerId, snippetId) < ownerPermission) {
            return ResponseEntity.status(401).build()
        }
        accessManagerClient.deletePermission(userId, snippetId)
        return ResponseEntity.ok().build()
    }

    /**
     * PUT /api/v1/users
     *
     * Create a user
     */
    @PutMapping("/users")
    @PreAuthorize("isAuthenticated()")
    fun createUser(
        authentication: Authentication,
        @RequestBody(required = false) body: Map<String, String>?,
    ): ResponseEntity<Any> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        val userEmail = body?.get("email") ?: jwt.getClaimAsString("email") ?: jwt.getClaimAsString("name") ?: userId

        userDataRepository.save(UserData(userId, userEmail))
        try {
            runnerClient.createUser(userId)
        } catch (e: Exception) {
            // Ignorar si ya existe en Runner
        }
        return ResponseEntity.ok().build()
    }

    /**
     * DELETE /api/v1/users
     *
     * Delete a user
     */
    @DeleteMapping("/users")
    @PreAuthorize("isAuthenticated()")
    fun deleteUser(authentication: Authentication): ResponseEntity<Any> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        val snippets = accessManagerClient.getPermissionsForUser(userId)
        if (snippets != null) {
            for (snippet in snippets.owned) {
                testRepository.deleteBySnippetId(snippet)
                snippetRepository.deleteBySnippetId(snippet)
                accessManagerClient.deletePermissionForSnippet(snippet)
            }
        }
        accessManagerClient.deletePermissionForUser(userId)
        runnerClient.deleteUser(userId)
        return ResponseEntity.ok().build()
    }

    /**
     * GET    /api/v1/snippets/{snippetId}/tests
     *
     * Get all tests for a snippet
     *
     * Response: a Map containing all test
     *
     *     {
     *       testId1: {
     *         testId: String
     *         snippetId: String,
     *         input: List<String>,
     *         output: List<String>,
     *         version: String,
     *         environment: Map<String, String>
     *       },
     *       testId2: {...},
     *       ...
     *     }
     */
    @GetMapping("/snippets/{snippetId}/tests")
    @PreAuthorize("isAuthenticated()")
    fun getAllTests(
        authentication: Authentication,
        @PathVariable snippetId: String,
    ): ResponseEntity<Map<String, Test>> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        if (getAuthorization(userId, snippetId) < sharedPermission) {
            return ResponseEntity.status(401).build()
        }
        val response = mutableMapOf<String, Test>()
        val tests = testRepository.findTestIdsBySnippetId(snippetId)
        for (test in tests) {
            response[test] = testRepository.findById(test).get()
        }
        return ResponseEntity.ok(response)
    }

    /**
     * POST   /api/v1/snippets/{snippetId}/tests
     *
     * Create a test
     *
     * Request:
     *
     *     {
     *       input: List<String>,
     *       expected: List<String>,
     *       version: String,
     *       environment: Map<String, String>
     *     }
     * Response
     *
     *     {
     *       testId: {testId}
     *     }
     */
    @PostMapping("/snippets/{snippetId}/tests")
    @PreAuthorize("isAuthenticated()")
    fun createTest(
        authentication: Authentication,
        @PathVariable snippetId: String,
        @RequestBody testData: CreateTestRequest,
    ): ResponseEntity<CreateTestResponse> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        if (getAuthorization(userId, snippetId) < ownerPermission) {
            return ResponseEntity.status(401).build()
        }
        val testId = UUID.randomUUID().toString()
        val test =
            Test(
                testId,
                snippetId,
                testData.input,
                testData.expected,
                testData.version,
                testData.environment,
            )
        testRepository.save(test)
        return ResponseEntity.ok(CreateTestResponse(testId))
    }

    /**
     * PUT    /api/v1/snippets/{snippetId}/tests/{testId}
     *
     * Start execution of a test
     *
     * Response:
     *
     *     {
     *       actual: List<String>,
     *       result: TestResult (String enum),
     *       message: String
     *     }
     */
    @PutMapping("/snippets/{snippetId}/tests/{testId}")
    @PreAuthorize("isAuthenticated()")
    fun runTest(
        authentication: Authentication,
        @PathVariable snippetId: String,
        @PathVariable testId: String,
    ): ResponseEntity<RunTestResponse> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        if (getAuthorization(userId, snippetId) < sharedPermission) {
            return ResponseEntity.status(401).build()
        }
        val test = testRepository.findById(testId).get()
        val result =
            runnerClient.runTest(
                snippetId,
                testId,
                test.version,
                test.environment,
                test.input,
                test.output,
            )
        return ResponseEntity.ok().body(result)
    }

    /**
     * DELETE /api/v1/snippets/{snippetId}/tests/{testId}
     *
     * Delete a test
     */
    @DeleteMapping("/snippets/{snippetId}/tests/{testId}")
    @PreAuthorize("isAuthenticated()")
    fun removeTest(
        authentication: Authentication,
        @PathVariable snippetId: String,
        @PathVariable testId: String,
    ): ResponseEntity<Any> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        if (getAuthorization(userId, snippetId) < ownerPermission) {
            return ResponseEntity.status(401).build()
        }
        testRepository.deleteById(testId)
        return ResponseEntity.ok().build()
    }

    /**
     * POST   /api/v1/snippets/{snippetId}/execution
     *
     * Start execution of a snippet
     *
     * Request:
     *
     *     {
     *       environment: Map<String, String>
     *       version: String
     *     }
     *
     * Response:
     *
     *     {
     *       status: String (COMPLETED/OUTPUT/WAITING/ERROR),
     *       message: List<String>
     *     }
     */
    @PostMapping("/snippets/{snippetId}/execution")
    @PreAuthorize("isAuthenticated()")
    fun runSnippet(
        authentication: Authentication,
        @PathVariable snippetId: String,
        @RequestBody request: SnippetRunRequest,
    ): ResponseEntity<StartExecutionResponse> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        if (getAuthorization(userId, snippetId) < sharedPermission) {
            return ResponseEntity.status(401).build()
        }
        val snippet = snippetRepository.findById(snippetId)
        if (snippet.isEmpty) {
            return ResponseEntity.notFound().build()
        }
        val output = runnerClient.runSnippet(snippetId, userId, request.version, request.environment)

        return ResponseEntity.ok().body(output)
    }

    /**
     * POST   /api/v1/snippets/{snippetId}/execution/input
     *
     * Send input to snippet execution
     *
     * Request:
     *
     *     {
     *       input: String
     *     }
     */
    @PostMapping("/snippets/{snippetId}/execution/input")
    @PreAuthorize("isAuthenticated()")
    fun sendInput(
        authentication: Authentication,
        @PathVariable snippetId: String,
        @RequestBody request: InputSendRequest,
    ): ResponseEntity<Any> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        if (getAuthorization(userId, snippetId) < sharedPermission) {
            return ResponseEntity.status(401).build()
        }
        val snippet = snippetRepository.findById(snippetId)
        if (snippet.isEmpty) {
            return ResponseEntity.notFound().build()
        }
        runnerClient.sendInput(snippetId, userId, request.input)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/snippets/{snippetId}/execution")
    @PreAuthorize("isAuthenticated()")
    fun cancelSnippetExecution(
        authentication: Authentication,
        @PathVariable snippetId: String,
        @RequestBody request: CancelExecutionRequest,
    ): ResponseEntity<Any> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        if (getAuthorization(userId, snippetId) < sharedPermission) {
            return ResponseEntity.status(401).build()
        }
        val snippet = snippetRepository.findById(snippetId)
        if (snippet.isEmpty) {
            return ResponseEntity.notFound().build()
        }
        runnerClient.cancelExecution(snippetId, userId)
        return ResponseEntity.ok().build()
    }

    /**
     * GET    /api/v1/snippets/{snippetId}/run/status
     *
     * Get the current status of a snippet execution.
     *
     * Response:
     *
     *     {
     *       status: String (COMPLETED/OUTPUT/WAITING/ERROR),
     *       message: List<String>
     *     }
     */
    @GetMapping("/snippets/{snippetId}/run/status")
    @PreAuthorize("isAuthenticated()")
    fun getExecutionStatus(
        authentication: Authentication,
        @PathVariable snippetId: String,
    ): ResponseEntity<StartExecutionResponse> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        if (getAuthorization(userId, snippetId) < sharedPermission) {
            return ResponseEntity.status(401).build()
        }
        val snippet = snippetRepository.findById(snippetId)
        if (snippet.isEmpty) {
            return ResponseEntity.notFound().build()
        }
        val output = runnerClient.getExecutionStatus(snippetId)
        return ResponseEntity.ok().body(output)
    }

    /**
     * PUT    /api/v1/rules
     *
     * Modify task rules
     *
     * Request:
     *
     *     {
     *         task: String (formatting/linting)
     *         language: String
     *         rules: Map<String, Any>
     *     }
     */
    @PutMapping("/rules")
    @PreAuthorize("isAuthenticated()")
    fun updateRules(
        authentication: Authentication,
        @RequestBody request: SnippetUpdateRequest,
    ): ResponseEntity<Any> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject

        val userSnippets =
            accessManagerClient.getPermissionsForUser(userId) ?: return ResponseEntity.status(404).build()
        runnerClient.patchRules(userId, request.task, request.language, request.rules)
        snippetTaskProducer.publish(userId, userSnippets.owned, request.language, request.task)
        return ResponseEntity.ok().build()
    }

    /**
     * PUT /api/c1/snippets/{snippetId}/task/{task}
     *
     * Apply a synchronous task to a snippet
     *
     * Returns the raw content of the processed snippet
     */
    @PutMapping("/snippets/{snippetId}/task/{task}")
    fun synchronousTask(
        authentication: Authentication,
        @PathVariable snippetId: String,
        @PathVariable task: String,
    ): ResponseEntity<String> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        if (getAuthorization(userId, snippetId) < ownerPermission) {
            return ResponseEntity.status(401).build()
        }
        val processedSnippet = runnerClient.callTask(snippetId, task)
        return ResponseEntity.ok().body(processedSnippet)
    }

    // ...

    /**
     * GET    /api/v1/rules?task={task}&language={language}
     *
     * Get all rules for a user
     *
     * Response:
     *
     *     {
     *         rule1: {val1}
     *         rule2: {val2}
     *         ...
     *     }
     */
    @GetMapping("/rules")
    @PreAuthorize("isAuthenticated()")
    fun getRules(
        authentication: Authentication,
        @RequestParam task: String,
        @RequestParam language: String,
    ): ResponseEntity<Map<String, Any>?> {
        val jwt = authentication.principal as Jwt
        val userId = jwt.subject
        try {
            val rules = runnerClient.getRules(userId, task, language)
            return ResponseEntity.ok(rules)
        } catch (e: HttpClientErrorException.NotFound) {
            runnerClient.registerUser(userId)
            val rules = runnerClient.getRules(userId, task, language)
            return ResponseEntity.ok(rules)
        }
    }
// ...

// //////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @PostMapping("/testing/separator")
    fun printSeparator() {
        println(
            "###############################################################\n" +
                    "# SEPARATOR SEPARATOR SEPARATOR SEPARATOR SEPARATOR SEPARATOR #\n" +
                    "###############################################################",
        )
    }

    @PostMapping("/testing")
    fun sendTestingMessage() {
        val requestId = MDC.get("requestId") ?: "unknown"
        val payload: Map<String, String> =
            mapOf(
                "task" to "test",
                "userId" to "userId",
                "snippetId" to "it",
                "language" to "language",
            )
        logger.info("[SNIPPET-APP] Request $requestId - Publishing test message to stream '$streamKey' with payload: $payload")
        try {
            redisTemplate.opsForStream<String, String>().add(streamKey, payload)
            logger.debug("[SNIPPET-APP] Request $requestId - Redis STREAM ADD successful: $streamKey")
        } catch (ex: Exception) {
            logger.error("[SNIPPET-APP] Request $requestId - Redis error on STREAM ADD: $streamKey", ex)
        }
    }
}
