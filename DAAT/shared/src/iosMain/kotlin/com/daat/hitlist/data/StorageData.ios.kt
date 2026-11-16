package com.daat.hitlist.data

import com.daat.hitlist.platform.toNSData
import dev.gitlive.firebase.storage.Data

internal actual fun ByteArray.toStorageData(): Data = Data(toNSData())
