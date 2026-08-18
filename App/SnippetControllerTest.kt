package com.grupo14IngSis.snippetSearcherApp.controller

import com.grupo14IngSis.snippetSearcherApp.client.AccessManagerClient
import com.grupo14IngSis.snippetSearcherApp.client.RunnerClient
import com.grupo14IngSis.snippetSearcherApp.config.SecurityConfig
import com.grupo14IngSis.snippetSearcherApp.domain.Snippet
import com.grupo14IngSis.snippetSearcherApp.domain.UserData
import com.grupo14IngSis.snippetSearcherApp.domain.Test as SnippetTestDomain
import com.grupo14IngSis.snippetSearcherApp.dto.GetPermissionResponse
import com.grupo14IngSis.snippetSearcherApp.dto.GetPermissionsForUserResponse
import com.grupo14IngSis.snippetSearcherApp.repository.SnippetRepository
import com.grupo14IngSis.snippetSearcherApp.repository.TestRepository
import com.grupo14IngSis.snippetSearcherApp.repository.UserDataRepository
import com.grupo14IngSis.snippetSearcherApp.service.SnippetTaskProducer
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Optional

@WebMvcTest(SnippetController::class)
@Import(SecurityConfig::class)
@TestPropertySource(properties = ["redis.stream.key=testStream"])
class SnippetControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var accessManagerClient: AccessManagerClient

    @MockitoBean private lateinit var runnerClient: RunnerClient

    @MockitoBean private lateinit var snippetRepository: SnippetRepository

    @MockitoBean private lateinit var testRepository: TestRepository

    @MockitoBean private lateinit var userDataRepository: UserDataRepository

    @MockitoBean private lateinit var snippetTaskProducer: SnippetTaskProducer

    @MockitoBean private lateinit var redisTemplate: RedisTemplate<String, String>

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    private val userId = "testUser"

    private fun customJwt() =
        jwt().jwt {
            it.subject(userId)
            it.claim("name", "Test User")
            it.claim("email", "test@example.com")
        }

    @Test fun `getAllSnippets returns 200`() {
        `when`(accessManagerClient.getPermissionsForUser(userId))
            .thenReturn(GetPermissionsForUserResponse(userId, listOf("snippet1"), listOf()))
        `when`(snippetRepository.findById("snippet1"))
            .thenReturn(Optional.of(Snippet("snippet1", "Snippet 1", "kotlin", "snippet1")))

        mockMvc.perform(
            get("/api/v1/snippets")
                .with(customJwt()),
        )
            .andExpect(status().isOk)
    }

    @Test fun `registerSnippet returns 200`() {
        mockMvc.perform(
            put("/api/v1/snippets/snippet1")
                .with(customJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$userId","name":"testSnippet","language":"kotlin"}"""),
        )
            .andExpect(status().isOk)
    }

    @Test fun `deleteSnippet returns 200`() {
        `when`(accessManagerClient.getPermission(userId, "snippet1"))
            .thenReturn(GetPermissionResponse(userId, "snippet1", "owner"))

        mockMvc.perform(
            delete("/api/v1/snippets/snippet1")
                .with(customJwt()),
        )
            .andExpect(status().isOk)
    }

    @Test fun `shareSnippet returns 200`() {
        `when`(accessManagerClient.getPermission(userId, "snippet1"))
            .thenReturn(GetPermissionResponse(userId, "snippet1", "owner"))
        `when`(userDataRepository.findByUserNameIgnoreCase("otherUser"))
            .thenReturn(UserData("otherUser", "otherUser@mail.com"))

        mockMvc.perform(
            put("/api/v1/snippets/snippet1/permission")
                .with(customJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"otherUser"}"""),
        )
            .andExpect(status().isOk)
    }

    @Test fun `removeSnippetPermission returns 200`() {
        `when`(accessManagerClient.getPermission(userId, "snippet1"))
            .thenReturn(GetPermissionResponse(userId, "snippet1", "owner"))

        mockMvc.perform(
            delete("/api/v1/snippets/snippet1/permission/otherUser")
                .with(customJwt()),
        )
            .andExpect(status().isOk)
    }

    @Test fun `deleteUser returns 200`() {
        `when`(accessManagerClient.getPermissionsForUser(userId))
            .thenReturn(GetPermissionsForUserResponse(userId, listOf(), listOf()))

        mockMvc.perform(
            delete("/api/v1/users")
                .with(customJwt()),
        )
            .andExpect(status().isOk)
    }

    @Test fun `createUser returns 200`() {
        mockMvc.perform(
            put("/api/v1/users")
                .with(customJwt()),
        )
            .andExpect(status().isOk)
    }

    @Test fun `getAllTests returns 200`() {
        `when`(accessManagerClient.getPermission(userId, "snippet1"))
            .thenReturn(GetPermissionResponse(userId, "snippet1", "owner"))
        `when`(testRepository.findTestIdsBySnippetId("snippet1"))
            .thenReturn(listOf("test1"))
        `when`(testRepository.findById("test1"))
            .thenReturn(Optional.of(SnippetTestDomain("test1", "snippet1", listOf("1"), listOf("2"), "1.0", emptyMap())))

        mockMvc.perform(
            get("/api/v1/snippets/snippet1/tests")
                .with(customJwt()),
        )
            .andExpect(status().isOk)
    }

    @Test fun `createTest returns 200`() {
        `when`(accessManagerClient.getPermission(userId, "snippet1"))
            .thenReturn(GetPermissionResponse(userId, "snippet1", "owner"))

        mockMvc.perform(
            post("/api/v1/snippets/snippet1/tests")
                .with(customJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"input":["1"],"expected":["2"],"version":"1.0","environment":{}}"""),
        )
            .andExpect(status().isOk)
    }

    @Test fun `runTest returns 200`() {
        `when`(accessManagerClient.getPermission(userId, "snippet1"))
            .thenReturn(GetPermissionResponse(userId, "snippet1", "owner"))
        `when`(testRepository.findById("test1"))
            .thenReturn(Optional.of(SnippetTestDomain("test1", "snippet1", listOf("1"), listOf("2"), "1.0", emptyMap())))

        mockMvc.perform(
            put("/api/v1/snippets/snippet1/tests/test1")
                .with(customJwt()),
        )
            .andExpect(status().isOk)
    }

    @Test fun `removeTest returns 200`() {
        `when`(accessManagerClient.getPermission(userId, "snippet1"))
            .thenReturn(GetPermissionResponse(userId, "snippet1", "owner"))

        mockMvc.perform(
            delete("/api/v1/snippets/snippet1/tests/test1")
                .with(customJwt()),
        )
            .andExpect(status().isOk)
    }

    @Test fun `runSnippet returns 200`() {
        `when`(accessManagerClient.getPermission(userId, "snippet1"))
            .thenReturn(GetPermissionResponse(userId, "snippet1", "owner"))
        `when`(snippetRepository.findById("snippet1"))
            .thenReturn(Optional.of(Snippet("snippet1", "Snippet 1", "kotlin", "snippet1")))

        mockMvc.perform(
            post("/api/v1/snippets/snippet1/execution")
                .with(customJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"version":"1.0","environment":{}}"""),
        )
            .andExpect(status().isOk)
    }

    @Test fun `cancelSnippetExecution returns 200`() {
        `when`(accessManagerClient.getPermission(userId, "snippet1"))
            .thenReturn(GetPermissionResponse(userId, "snippet1", "owner"))
        `when`(snippetRepository.findById("snippet1"))
            .thenReturn(Optional.of(Snippet("snippet1", "Snippet 1", "kotlin", "snippet1")))

        mockMvc.perform(
            delete("/api/v1/snippets/snippet1/execution")
                .with(customJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$userId"}"""),
        )
            .andExpect(status().isOk)
    }

    @Test fun `updateRules returns 200`() {
        `when`(accessManagerClient.getPermissionsForUser(userId))
            .thenReturn(GetPermissionsForUserResponse(userId, listOf("snippet1"), listOf()))

        mockMvc.perform(
            put("/api/v1/rules")
                .with(customJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"task":"linting","language":"kotlin","rules":{"rule1":"val1"}}"""),
        )
            .andExpect(status().isOk)
    }

    @Test fun `getRules returns 200`() {
        `when`(runnerClient.getRules(userId, "linting", "kotlin"))
            .thenReturn(mapOf("rule1" to "val1"))

        mockMvc.perform(
            get("/api/v1/rules")
                .with(customJwt())
                .param("task", "linting")
                .param("language", "kotlin"),
        )
            .andExpect(status().isOk)
    }
}
