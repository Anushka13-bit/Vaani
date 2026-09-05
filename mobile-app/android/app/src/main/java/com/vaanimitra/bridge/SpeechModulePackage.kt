package com.vaanimitra.bridge

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/**
 * SpeechModulePackage — registers SpeechModule and RecognitionEventEmitter with React Native.
 * Must be added to MainApplication.kt's getPackages() list.
 */
class SpeechModulePackage : ReactPackage {

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> =
        listOf(
            SpeechModule(reactContext),
            RecognitionEventEmitter(reactContext),
        )

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        emptyList()
}
