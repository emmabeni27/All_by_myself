package com.grupo14IngSis.snippetSearcherApp.repository

import com.grupo14IngSis.snippetSearcherApp.domain.UserData
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserDataRepository : JpaRepository<UserData, String>{
    fun findByUserNameIgnoreCase(userName: String): UserData?
}
