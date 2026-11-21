package com.cairn.securepocket.ui.main

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [com.cairn.securepocket.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Before
  fun setup() {
    composeTestRule.setContent { MainScreen(FAKE_DATA) }
  }

  @Test
  fun firstItem_exists() {
    FAKE_DATA.forEach { composeTestRule.onNodeWithText("Hello $it !").assertExists() }
  }
}

private val FAKE_DATA = listOf("Sample1", "Sample2", "Sample3")
