# 제3자 소프트웨어 고지

기준일: 2026-10-09. 이 문서는 앱에 사용되는 제3자 소프트웨어에 대한 고지이며 프로젝트 자체의 소스 라이선스를 정하지 않습니다.

## 확인 범위

Gradle의 `releaseRuntimeClasspath` 실제 해결 결과에서 제약조건만 있는 행을 제외하고 선택된 모듈을 정리했습니다. 아래 90개 좌표에는 멀티플랫폼 중계 모듈과 BOM 등 메타데이터도 포함되며, APK에 90개 코드 라이브러리가 들어간다는 뜻은 아닙니다. debug 전용 UI tooling과 시험 APK 의존성은 release 목록에 포함하지 않습니다.

라이선스는 로컬 Gradle 캐시의 POM, JAR/AAR 내부 LICENSE와 NOTICE를 우선 확인했습니다. 개별 artifact에 고지 원문이 없으면 공식 저장소의 프로젝트 라이선스로 계열을 대조했습니다. 계열 원문으로 확인한 항목은 개별 버전의 모든 파일·추가 NOTICE까지 독립적으로 감사했다는 의미가 아닙니다. 의존성 변경 시 이 목록과 APK 내 고지도 함께 갱신해야 합니다.

## 라이선스와 출처

| 구성요소 | 라이선스 | 확인 근거 |
| --- | --- | --- |
| AndroidX Activity와 ProfileInstaller | Apache-2.0 | 캐시 AAR 내부 `META-INF/androidx/.../LICENSE.txt` |
| 그 밖의 AndroidX·Compose·Material3 모듈 | Apache-2.0 | 일부 캐시 POM 및 [AndroidX 공식 계열 LICENSE](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt). 개별 artifact 원문이 없는 항목은 계열 단위 확인 |
| Kotlin 표준 라이브러리 | Apache-2.0 | jdk7/jdk8 캐시 POM 및 [Kotlin v2.2.10 LICENSE](https://github.com/JetBrains/kotlin/blob/v2.2.10/license/LICENSE.txt) |
| Kotlin Coroutines | Apache-2.0 | 캐시 POM |
| JetBrains Java Annotations | Apache-2.0 | [공식 23.0.0 LICENSE](https://github.com/JetBrains/java-annotations/blob/23.0.0/LICENSE.txt) |
| OkHttp·Okio | Apache-2.0 | 캐시 POM. OkHttp의 Public Suffix List 고지는 아래에 별도 보존 |
| Gson | Apache-2.0 | JAR 내부 Maven POM |
| Error Prone Annotations | Apache-2.0 | JAR 내부 Maven POM |
| Guava ListenableFuture | Apache-2.0 | JAR 내부 Maven POM의 Guava 식별 및 [Guava 공식 계열 LICENSE](https://github.com/google/guava/blob/master/LICENSE). 개별 1.0 배포 원문은 계열 단위 확인 |
| jsoup | MIT | 캐시 POM과 JAR 내부 `META-INF/LICENSE`. 저작권과 허가문 원문을 아래에 보존 |

빌드·시험 소스는 Gradle Wrapper, Android Gradle Plugin, Kotlin Compose plugin, JUnit, AndroidX Test 및 MockWebServer도 사용합니다. 이 도구와 시험 의존성은 release APK의 실행 의존성 목록과 구분하며, 각 도구를 별도로 배포하는 경우 해당 배포본의 라이선스와 고지를 따라야 합니다. Gradle Wrapper 스크립트의 기존 Apache 라이선스 머리말을 보존합니다.

런처 도시락·달력 이미지는 이 프로젝트를 위해 생성한 이미지이며 포스웰의 공식 로고나 사이트 이미지를 복제한 자산이 아닙니다. 사이트에서 제공되는 웹 콘텐츠와 상표의 권리는 각 권리자에게 있습니다.

## 해결된 release 모듈

```text
androidx.activity:activity-compose:1.10.0
androidx.activity:activity-ktx:1.10.0
androidx.activity:activity:1.10.0
androidx.annotation:annotation-experimental:1.4.1
androidx.annotation:annotation-jvm:1.8.1
androidx.annotation:annotation:1.8.1
androidx.arch.core:core-common:2.2.0
androidx.arch.core:core-runtime:2.2.0
androidx.autofill:autofill:1.0.0
androidx.collection:collection-jvm:1.4.4
androidx.collection:collection-ktx:1.4.4
androidx.collection:collection:1.4.4
androidx.compose.animation:animation-android:1.7.6
androidx.compose.animation:animation-core-android:1.7.6
androidx.compose.animation:animation-core:1.7.6
androidx.compose.animation:animation:1.7.6
androidx.compose.foundation:foundation-android:1.7.6
androidx.compose.foundation:foundation-layout-android:1.7.6
androidx.compose.foundation:foundation-layout:1.7.6
androidx.compose.foundation:foundation:1.7.6
androidx.compose.material:material-icons-core-android:1.7.6
androidx.compose.material:material-icons-core:1.7.6
androidx.compose.material:material-icons-extended-android:1.7.6
androidx.compose.material:material-icons-extended:1.7.6
androidx.compose.material:material-ripple-android:1.7.0
androidx.compose.material:material-ripple:1.7.0
androidx.compose.material3:material3-android:1.3.1
androidx.compose.material3:material3:1.3.1
androidx.compose.runtime:runtime-android:1.7.6
androidx.compose.runtime:runtime-saveable-android:1.7.6
androidx.compose.runtime:runtime-saveable:1.7.6
androidx.compose.runtime:runtime:1.7.6
androidx.compose.ui:ui-android:1.7.6
androidx.compose.ui:ui-geometry-android:1.7.6
androidx.compose.ui:ui-geometry:1.7.6
androidx.compose.ui:ui-graphics-android:1.7.6
androidx.compose.ui:ui-graphics:1.7.6
androidx.compose.ui:ui-text-android:1.7.6
androidx.compose.ui:ui-text:1.7.6
androidx.compose.ui:ui-tooling-preview-android:1.7.6
androidx.compose.ui:ui-tooling-preview:1.7.6
androidx.compose.ui:ui-unit-android:1.7.6
androidx.compose.ui:ui-unit:1.7.6
androidx.compose.ui:ui-util-android:1.7.6
androidx.compose.ui:ui-util:1.7.6
androidx.compose.ui:ui:1.7.6
androidx.concurrent:concurrent-futures:1.1.0
androidx.core:core-ktx:1.13.1
androidx.core:core:1.13.1
androidx.customview:customview-poolingcontainer:1.0.0
androidx.emoji2:emoji2:1.3.0
androidx.graphics:graphics-path:1.0.1
androidx.interpolator:interpolator:1.0.0
androidx.lifecycle:lifecycle-common-java8:2.8.7
androidx.lifecycle:lifecycle-common-jvm:2.8.7
androidx.lifecycle:lifecycle-common:2.8.7
androidx.lifecycle:lifecycle-livedata-core:2.8.7
androidx.lifecycle:lifecycle-process:2.8.7
androidx.lifecycle:lifecycle-runtime-android:2.8.7
androidx.lifecycle:lifecycle-runtime-compose-android:2.8.7
androidx.lifecycle:lifecycle-runtime-compose:2.8.7
androidx.lifecycle:lifecycle-runtime-ktx-android:2.8.7
androidx.lifecycle:lifecycle-runtime-ktx:2.8.7
androidx.lifecycle:lifecycle-runtime:2.8.7
androidx.lifecycle:lifecycle-viewmodel-android:2.8.7
androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7
androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7
androidx.lifecycle:lifecycle-viewmodel:2.8.7
androidx.profileinstaller:profileinstaller:1.4.0
androidx.savedstate:savedstate-ktx:1.2.1
androidx.savedstate:savedstate:1.2.1
androidx.startup:startup-runtime:1.1.1
androidx.tracing:tracing:1.0.0
androidx.versionedparcelable:versionedparcelable:1.1.1
com.google.code.gson:gson:2.11.0
com.google.errorprone:error_prone_annotations:2.27.0
com.google.guava:listenablefuture:1.0
com.squareup.okhttp3:okhttp:4.12.0
com.squareup.okio:okio-jvm:3.6.0
com.squareup.okio:okio:3.6.0
org.jetbrains:annotations:23.0.0
org.jetbrains.kotlin:kotlin-stdlib-common:2.2.10
org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.10
org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10
org.jetbrains.kotlin:kotlin-stdlib:2.2.10
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2
org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
org.jsoup:jsoup:1.18.3
```

## Apache License 2.0

아래 원문은 캐시된 AndroidX Activity AAR의 LICENSE.txt에서 보존했습니다. 이 라이선스는 위 표의 Apache-2.0 구성요소에 적용되며 본 앱 자체의 라이선스를 대신하지 않습니다.

```text
Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS
```

## jsoup MIT 원문

```text
The MIT License

Copyright (c) 2009-2024 Jonathan Hedley <https://jsoup.org/>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## OkHttp Public Suffix List 고지

다음은 OkHttp JAR에 포함된 NOTICE 원문입니다. Public Suffix List는 MPL-2.0의 적용을 받으며 소스와 라이선스는 아래 공식 주소에서 확인할 수 있습니다. 이 앱에서는 해당 목록을 직접 수정하지 않습니다.

```text
Note that publicsuffixes.gz is compiled from The Public Suffix List:
https://publicsuffix.org/list/public_suffix_list.dat

It is subject to the terms of the Mozilla Public License, v. 2.0:
https://mozilla.org/MPL/2.0/
```

- 소스: https://publicsuffix.org/list/public_suffix_list.dat
- 라이선스: https://mozilla.org/MPL/2.0/

