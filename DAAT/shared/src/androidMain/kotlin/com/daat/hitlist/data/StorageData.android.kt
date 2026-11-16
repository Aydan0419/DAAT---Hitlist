package com.daat.hitlist.data

import dev.gitlive.firebase.storage.Data

internal actual fun ByteArray.toStorageData(): Data = Data(this)
