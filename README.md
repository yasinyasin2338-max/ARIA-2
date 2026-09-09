# Orbis AI

Phase 1 foundation for a private Android voice-first hybrid AI assistant.

## Current implementation
- Kotlin + Jetpack Compose
- Shared AI engine abstraction
- Three configurable agent identities
- Local demo engine for deterministic UI testing
- Voice state machine foundation
- Microphone runtime permission entry point
- arm64-v8a target for modern flagship devices
- AI model binaries intentionally kept outside the APK

## Next engineering stages
1. Streaming VAD + STT
2. Streaming TTS + true barge-in cancellation
3. llama.cpp/GGUF offline inference
4. Online/offline router
5. Persistent encrypted memory/RAG
6. Tool and Android action layer
7. Office/avatar renderer
8. Performance profiling and device testing

The repository is not claimed to contain a built APK until a real Android/Gradle build has been executed and verified.
