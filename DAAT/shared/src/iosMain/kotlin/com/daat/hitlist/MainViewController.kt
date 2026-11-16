package com.daat.hitlist

import androidx.compose.ui.window.ComposeUIViewController
import com.daat.hitlist.ui.App

/** The whole app as one UIKit view controller, hosted by the SwiftUI shell. */
fun MainViewController() = ComposeUIViewController { App() }
