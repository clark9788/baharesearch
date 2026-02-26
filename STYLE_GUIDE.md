# TranscriberA Coding Style Guide

## Purpose
This document defines the coding conventions for the TranscriberA medical transcription Android application. All code contributions should follow these patterns to maintain consistency and readability.

**Target Audience:** Developers (human or AI) contributing to this codebase.

---

## Naming Conventions

### Classes
- **Format:** PascalCase
- **Pattern:** Descriptive nouns representing the class's responsibility
- **Examples:**
  ```java
  public class FileManager
  public class AudioRecorder
  public class GCloudTranscriber
  public class EncryptionManager
  ```

### Methods
- **Format:** camelCase
- **Pattern:** Verb phrases describing the action
- **Examples:**
  ```java
  public static String sanitizeComponent(String value)
  public File start() throws IOException
  public static void initialize(Context context)
  public void encryptFile(File source, File destination)
  ```

### Constants
- **Format:** UPPER_SNAKE_CASE
- **Modifier:** `private static final` (or `public static final` for config)
- **Examples:**
  ```java
  private static final String TAG = "FileManager";
  private static final int RECORDER_SAMPLE_RATE = 16000;
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  public static final String LANGUAGE_CODE = "en-US";
  ```

### Variables
- **Format:** camelCase
- **Pattern:** Descriptive nouns, avoid abbreviations unless standard (e.g., `fos` for FileOutputStream)
- **Examples:**
  ```java
  private boolean isRecording = false;
  private File currentFile;
  private Context context;
  ```

### Packages
- **Format:** lowercase, dot-separated
- **Structure:** `com.transcriber.{domain}`
- **Domains:**
  - `audio` - Audio recording functionality
  - `cloud` - Cloud service integrations
  - `file` - File management and I/O
  - `audit` - Audit logging
  - `config` - Configuration constants
  - `security` - Encryption, authentication, key management
  - `template` - Template management
  - `text` - Text processing and cleaning
  - `api` - REST API clients (for Firebase backend)
  - `auth` - Firebase authentication wrappers

---

## Documentation Style

### Class-Level Documentation
- **Format:** Javadoc comment above class declaration
- **Content:** Single-line description of class purpose (concise, no fluff)
- **Pattern:**
  ```java
  /**
   * Transcription file management and secure deletion helpers for Android.
   */
  public class FileManager {
  ```

### Method-Level Documentation
- **Format:** Javadoc comment above public methods
- **Content:** Single-line description of what the method does
- **Pattern:** Omit `@param` and `@return` tags (keep it simple)
- **Example:**
  ```java
  /**
   * Sanitize a component (patient name or DOB) for use in filenames.
   */
  public static String sanitizeComponent(String value) {
  ```

### Private Methods
- **Documentation:** Optional - only document if logic is complex or non-obvious
- **Preference:** Self-documenting code (clear method names) over comments

### Inline Comments
- **Use sparingly:** Code should be self-explanatory
- **When to use:**
  - Complex algorithms that aren't immediately clear
  - Security-critical operations (e.g., "Wipe encryption key from memory")
  - Workarounds for Android/library bugs
- **Format:** Single-line `//` comments above the line they explain

---

## Code Formatting

### Indentation
- **Style:** 4 spaces (no tabs)
- **Braces:** Opening brace on same line (K&R style)
  ```java
  public void method() {
      if (condition) {
          // code
      }
  }
  ```

### Line Length
- **Soft limit:** 120 characters
- **Hard limit:** No enforced limit, but break long lines for readability

### Blank Lines
- **Between methods:** 1 blank line
- **Between logical sections within method:** 1 blank line
- **After class declaration:** 1 blank line before first field/method

### Import Organization
- **Order:**
  1. Android framework imports (`android.*`)
  2. AndroidX imports (`androidx.*`)
  3. Third-party libraries (`com.google.*`, etc.)
  4. Internal package imports (`com.transcriber.*`)
  5. Java standard library (`java.*`, `javax.*`)
- **Grouping:** Separate groups with blank line
- **Sorting:** Alphabetical within each group

**Example:**
```java
import android.content.Context;
import android.util.Log;

import androidx.biometric.BiometricPrompt;

import com.google.cloud.speech.v1.SpeechClient;

import com.transcriber.audit.AuditLogger;
import com.transcriber.config.Config;

import java.io.File;
import java.io.IOException;
```

---

## Architecture Patterns

### Static Utility Classes
**When to use:** Stateless operations, helper functions, no instance data needed

**Pattern:**
```java
public class FileManager {
    private static final String TAG = "FileManager";

    // Private constructor prevents instantiation
    private FileManager() {}

    public static String sanitizeComponent(String value) {
        // Implementation
    }
}
```

**Examples:** `FileManager`, `EncryptionManager`, `TranscriptionCleaner`

### Instance-Based Classes
**When to use:** Stateful operations, needs context, manages resources

**Pattern:**
```java
public class AudioRecorder {
    private static final String TAG = "AudioRecorder";
    private boolean isRecording = false;
    private Context context;

    public AudioRecorder(Context context) {
        this.context = context;
    }

    public File start() throws IOException {
        // Implementation
    }
}
```

**Examples:** `AudioRecorder`, `BiometricAuthHelper`

### Singleton Pattern
**When to use:** Global state, expensive initialization (e.g., cloud clients)

**Pattern:**
```java
public class GCloudTranscriber {
    private static final String TAG = "GCloudTranscriber";
    private static SpeechClient speechClient;

    public static void initialize(Context context) throws IOException {
        if (speechClient == null) {
            // Lazy initialization
        }
    }
}
```

**Examples:** `GCloudTranscriber`, `AuditLogger`

---

## Error Handling

### Exception Handling
- **Prefer:** Declare `throws IOException` for checked exceptions (let caller handle)
- **Avoid:** Catching and suppressing exceptions without logging
- **Pattern:**
  ```java
  public static void saveTranscription(File file, String content) throws IOException {
      try (FileOutputStream fos = new FileOutputStream(file);
           OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
          writer.write(content);
      }
      // IOException propagates to caller
  }
  ```

### Logging
- **Use:** `android.util.Log` for all logging
- **Tag:** Class name as `private static final String TAG = "ClassName";`
- **Levels:**
  - `Log.e()` - Errors (exceptions, failures)
  - `Log.w()` - Warnings (recoverable issues)
  - `Log.i()` - Info (high-level operations: "Recording started")
  - `Log.d()` - Debug (detailed flow, remove before production)
- **Pattern:**
  ```java
  Log.e(TAG, "Failed to encrypt file: " + file.getName(), exception);
  Log.i(TAG, "Transcription saved: " + file.getAbsolutePath());
  ```

### Null Handling
- **Defensive checks:** Guard against null inputs
- **Pattern:**
  ```java
  if (value == null || value.trim().isEmpty()) {
      return "unknown";
  }
  ```
- **Annotations:** Use `@Nullable` and `@NonNull` where appropriate (optional)

---

## Resource Management

### File I/O
**Always use try-with-resources:**
```java
try (FileOutputStream fos = new FileOutputStream(file);
     OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
    writer.write(content);
}
```

### Encoding
**Always specify:** `StandardCharsets.UTF_8` (never use platform default)

### Background Operations
- **Audio recording:** Separate thread (`Thread` or `AsyncTask`)
- **Encryption/Decryption:** Background thread (use `AsyncTask` or Kotlin Coroutines)
- **Network calls:** Already async (Cloud Functions, Retrofit)

---

## Security Practices

### PHI Handling
- **Never log PHI:** Patient names, DOB, transcription content
- **Safe to log:** Filenames (if UUIDs), operation types, status messages
- **Pattern:**
  ```java
  // GOOD
  Log.i(TAG, "Transcription saved: " + uuid + ".enc");

  // BAD - Don't log patient data
  Log.i(TAG, "Saved transcription for patient: " + patientName);
  ```

### Credentials
- **Never commit:** `google-services.json`, `google_credentials.json`, API keys
- **Storage:** Server-side only (Cloud Functions, Cloud Secret Manager)
- **Android:** Use Firebase ID tokens, not service account keys

### Encryption
- **Algorithm:** AES-256-GCM (authenticated encryption)
- **Keys:** Android Keystore (hardware-backed)
- **Randomness:** `SecureRandom` for IVs and salts (never reuse IVs)

---

## Testing Conventions

### Unit Tests
- **Location:** `app/src/test/java/com/transcriber/`
- **Naming:** `ClassNameTest.java` (e.g., `FileManagerTest.java`)
- **Method naming:** `testMethodName_Scenario_ExpectedBehavior()`
  ```java
  @Test
  public void testSanitizeComponent_NullInput_ReturnsUnknown() {
      assertEquals("unknown", FileManager.sanitizeComponent(null));
  }
  ```

### Integration Tests
- **Location:** `app/src/androidTest/java/com/transcriber/`
- **Focus:** End-to-end flows (record → transcribe → save)

---

## Dependency Management

### Gradle Dependencies
- **Use BOM:** Firebase BOM for version consistency
- **Specify versions:** For non-BOM dependencies (e.g., OkHttp 4.12.0)
- **Group by category:**
  ```gradle
  // Firebase
  implementation platform('com.google.firebase:firebase-bom:32.7.0')
  implementation 'com.google.firebase:firebase-auth'

  // HTTP Client
  implementation 'com.squareup.okhttp3:okhttp:4.12.0'

  // Biometric
  implementation 'androidx.biometric:biometric:1.1.0'
  ```

---

## File Organization

### Directory Structure
```
app/src/main/java/com/transcriber/
├── audio/              # Audio recording
├── audit/              # Audit logging
├── cloud/              # Google Cloud integration
├── config/             # Configuration constants
├── file/               # File management
├── security/           # Encryption, authentication
├── template/           # Template management
├── text/               # Text processing
├── api/                # REST API clients (future)
└── MainActivity.java   # Main activity
```

### File Naming
- **Classes:** Match class name (e.g., `FileManager.java`)
- **Resources:** lowercase_with_underscores (e.g., `activity_main.xml`)

---

## Anti-Patterns to Avoid

### Don't Use
- ❌ **Magic numbers:** Use named constants
  ```java
  // BAD
  if (value > 5) { }

  // GOOD
  private static final int MAX_RETRIES = 5;
  if (value > MAX_RETRIES) { }
  ```

- ❌ **Abbreviations:** Unless standard (e.g., `IOException`)
  ```java
  // BAD
  String ptName;

  // GOOD
  String patientName;
  ```

- ❌ **God classes:** Classes doing too much (split into focused classes)

- ❌ **Deep nesting:** Refactor into helper methods
  ```java
  // BAD
  if (a) {
      if (b) {
          if (c) {
              // deeply nested
          }
      }
  }

  // GOOD - guard clauses
  if (!a) return;
  if (!b) return;
  if (!c) return;
  // main logic
  ```

---

## Code Review Checklist

Before submitting code, verify:
- [ ] Naming follows conventions (camelCase methods, PascalCase classes)
- [ ] Public methods have Javadoc comments
- [ ] No PHI in log statements
- [ ] Resources use try-with-resources
- [ ] Constants defined (no magic numbers)
- [ ] Encoding specified (UTF-8)
- [ ] Exceptions properly handled
- [ ] Files organized in correct package
- [ ] No credentials in code

---

## Version History
- **2026-01-24:** Initial version based on existing codebase patterns (FileManager, AudioRecorder, GCloudTranscriber)
- **Future:** Update as patterns evolve with Task 2 (encryption) and Task 1 (Firebase auth) implementation

---

## Questions?
When in doubt, refer to existing code:
- **File management patterns:** See `FileManager.java`
- **Instance-based patterns:** See `AudioRecorder.java`
- **Static utility patterns:** See `TranscriptionCleaner.java`
- **Cloud integration patterns:** See `GCloudTranscriber.java`
