/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.psi.stubs

import com.google.common.hash.HashCode
import com.google.common.hash.Hashing
import com.intellij.index.PrebuiltIndexProviderBase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeExtension
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.indexing.FileContent
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.DataInputOutputUtil
import com.intellij.util.io.KeyDescriptor
import com.intellij.util.io.PersistentHashMap
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly
import java.io.DataInput
import java.io.DataOutput
import java.io.File

/**
 * @author traff
 */

val EP_NAME: String = "com.intellij.filetype.prebuiltStubsProvider"

object PrebuiltStubsProviders : FileTypeExtension<PrebuiltStubsProvider>(EP_NAME)

@ApiStatus.Experimental
interface PrebuiltStubsProvider {
  fun findStub(fileContent: FileContent): Stub?
}

class FileContentHashing {
  private val hashing = Hashing.sha256()

  fun hashString(fileContent: FileContent): HashCode = hashing.hashBytes(fileContent.content)!!
}


class HashCodeDescriptor : HashCodeExternalizers(), KeyDescriptor<HashCode> {
  override fun getHashCode(value: HashCode): Int = value.hashCode()

  override fun isEqual(val1: HashCode, val2: HashCode): Boolean = val1 == val2

  companion object {
    val instance: HashCodeDescriptor = HashCodeDescriptor()
  }
}

open class HashCodeExternalizers : DataExternalizer<HashCode> {
  override fun save(out: DataOutput, value: HashCode) {
    val bytes = value.asBytes()
    DataInputOutputUtil.writeINT(out, bytes.size)
    out.write(bytes, 0, bytes.size)
  }

  override fun read(`in`: DataInput): HashCode {
    val len = DataInputOutputUtil.readINT(`in`)
    val bytes = ByteArray(len)
    `in`.readFully(bytes)
    return HashCode.fromBytes(bytes)
  }
}

class StubTreeExternalizer : DataExternalizer<SerializedStubTree> {
  override fun save(out: DataOutput, value: SerializedStubTree) {
    value.write(out)
  }

  override fun read(`in`: DataInput): SerializedStubTree = SerializedStubTree(`in`)
}

abstract class PrebuiltStubsProviderBase : PrebuiltIndexProviderBase<SerializedStubTree>(), PrebuiltStubsProvider {

  private var mySerializationManager: SerializationManagerImpl? = null

  protected abstract val stubVersion: Int

  override val indexName: String get() = SDK_STUBS_STORAGE_NAME

  override val indexExternalizer: StubTreeExternalizer get() = StubTreeExternalizer()

  companion object {
    val PREBUILT_INDICES_PATH_PROPERTY: String = "prebuilt_indices_path"
    val SDK_STUBS_STORAGE_NAME: String = "sdk-stubs"
    private val LOG = Logger.getInstance("#com.intellij.psi.stubs.PrebuiltStubsProviderBase")
  }

  override fun openIndexStorage(indexesRoot: File): PersistentHashMap<HashCode, SerializedStubTree>? {
    val versionInFile = FileUtil.loadFile(File(indexesRoot, indexName + ".version"))

    if (Integer.parseInt(versionInFile) == stubVersion) {
      mySerializationManager = SerializationManagerImpl(File(indexesRoot, indexName + ".names"))

      Disposer.register(ApplicationManager.getApplication(), mySerializationManager!!)

      return super.openIndexStorage(indexesRoot)
    }
    else {
      LOG.error("Prebuilt stubs version mismatch: $versionInFile, current version is $stubVersion")
      return null
    }
  }

  override fun findStub(fileContent: FileContent): Stub? {
    var stub: Stub? = null
    try {
      val stubTree = get(fileContent)
      if (stubTree != null) {
        stub = stubTree.getStub(false, mySerializationManager!!)
      }
    }
    catch (e: SerializerNotFoundException) {
      LOG.error("Can't deserialize stub tree", e)
    }

    if (stub != null) {
      return stub
    }
    return null
  }
}

@TestOnly
fun PrebuiltStubsProviderBase.reset() {
  this.init()
}