# Third-party notices

Fanos is free software under the GNU General Public License, version 3 or later (see LICENSE). It is built
with the work of others, listed here with their licences. The licence texts referred to are at the end of this
file, except the GNU GPL, which is in LICENSE.

## Libraries under the Apache License, Version 2.0

Shipped inside the app:

- AndroidX: Core, Activity, Lifecycle, Navigation, WorkManager, Glance, DataStore, Room, SQLite (bundled),
  Hilt (Jetpack), Media3 — Copyright The Android Open Source Project
- Jetpack Compose (UI, Foundation, Material 3, Material Icons) — Copyright The Android Open Source Project
- Dagger and Hilt — Copyright Google LLC
- Kotlin standard library, kotlinx.coroutines, kotlinx.serialization, kotlinx-atomicfu — Copyright JetBrains s.r.o.
  and contributors
- OkHttp and Okio — Copyright Square, Inc.
- Coil — Copyright Coil Contributors
- Guava — Copyright The Guava Authors
- JSpecify annotations, Error Prone annotations, JetBrains annotations
- javax.inject and Jakarta Inject
- sherpa-onnx 1.13.8 — Copyright Xiaomi Corporation and the k2-fsa authors (see the next section)
- Apache Commons Compress, IO, Codec and Lang — The Apache Software Foundation, whose NOTICE files read:

    Apache Commons Compress
    Copyright 2002-2025 The Apache Software Foundation

    This product includes software developed at
    The Apache Software Foundation (https://www.apache.org/).

    Apache Commons IO
    Copyright 2002-2025 The Apache Software Foundation

    This product includes software developed at
    The Apache Software Foundation (https://www.apache.org/).

    Apache Commons Codec
    Copyright 2002-2025 The Apache Software Foundation

    This product includes software developed at
    The Apache Software Foundation (https://www.apache.org/).

    Apache Commons Lang
    Copyright 2001-2025 The Apache Software Foundation

    This product includes software developed at
    The Apache Software Foundation (https://www.apache.org/).

## The speech engine: sherpa-onnx and what is built into it

Natural voices are read with sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx, Apache-2.0), version 1.13.8,
shipped as the native library libsherpa-onnx-jni.so. That library has these built into it:

- ONNX Runtime — Copyright (c) Microsoft Corporation, MIT License
  (https://github.com/microsoft/onnxruntime; its own third-party notices are in that repository's
  ThirdPartyNotices.txt)
- eSpeak NG — Copyright the eSpeak NG contributors, GNU General Public License, version 3 or later
  (https://github.com/espeak-ng/espeak-ng). Its source, and the source of sherpa-onnx 1.13.8 that builds it in,
  are at the addresses above.
- kaldifst and OpenFst — Apache-2.0 (https://github.com/k2-fsa/kaldifst, https://www.openfst.org)
- kaldi-decoder — Apache-2.0 (https://github.com/k2-fsa/kaldi-decoder)
- simple-sentencepiece — Apache-2.0 (https://github.com/pkufool/simple-sentencepiece)
- piper-phonemize — Copyright (c) 2023 Michael Hansen, MIT License (https://github.com/rhasspy/piper-phonemize)
- cppjieba — Copyright (c) 2013 Yanyi Wu, MIT License (https://github.com/yanyiwu/cppjieba)

## Natural voices, downloaded on request

The voice pack kokoro-int8-en-v0_19 is downloaded from the sherpa-onnx releases on GitHub only when you ask for
it, and is not part of the app:

- Kokoro-82M — hexgrad, Apache-2.0 (https://huggingface.co/hexgrad/Kokoro-82M), converted to ONNX and int8 by
  the k2-fsa authors
- eSpeak NG data (espeak-ng-data) — GNU General Public License, version 3 or later

## Other libraries

- jsoup — Copyright (c) 2009-2026 Jonathan Hedley <https://jsoup.org/>, MIT License (text below)
- Checker Framework qualifiers (checker-qual) — Copyright 2004-present the Checker Framework developers,
  MIT License
- The Public Suffix List, bundled by OkHttp — Mozilla Foundation, Mozilla Public License, version 2.0. This
  Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
  distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. The list itself is at
  https://publicsuffix.org/list/.
- SQLite, bundled by androidx.sqlite — public domain (https://sqlite.org)

## Fonts

The reading fonts are under the SIL Open Font License, Version 1.1, each with its own copyright notice and a copy
of the licence in the app's assets (reader/fonts/licenses):

- Literata — Copyright 2017 The Literata Project Authors
- Lora — Copyright 2011 The Lora Project Authors, Reserved Font Name "Lora"
- Crimson Pro — Copyright 2018 The Crimson Pro Project Authors
- EB Garamond — Copyright 2017 The EB Garamond Project Authors
- Bitter — Copyright 2011 The Bitter Project Authors, Reserved Font Name "Bitter Pro"
- Atkinson Hyperlegible Next — Copyright 2020-2024 The Atkinson Hyperlegible Next Project Authors
- Lexend — Copyright 2018 The Lexend Project Authors, Reserved Font Name "RevReading Lexend"

## Icons

Icons from Google's Material Icons, Apache-2.0 (https://fonts.google.com/icons). The app's own lantern icon is
its own.

## The built-in dictionary

The dictionary is made from Open English WordNet, 2025 edition (https://en-word.net/,
https://github.com/globalwordnet/english-wordnet), Copyright (c) 2019-present, The Open English WordNet Team,
licensed under the Creative Commons Attribution 4.0 International License
(https://creativecommons.org/licenses/by/4.0/). It is provided as-is, without warranties of any kind. It has
been modified for Fanos: words and phrases are kept with up to three senses for each part of speech, their
pronunciations and irregular forms; definitions are given a capital letter and a full stop; everything else is
left out; and the result is stored in a format of Fanos's own (tools/dictionary/make_dictionary.py).

Open English WordNet is derived from Princeton WordNet, whose licence terms apply to the underlying data:

    WordNet 3.1 Copyright 2011 by Princeton University.  All rights reserved.

    THIS SOFTWARE AND DATABASE IS PROVIDED "AS IS" AND PRINCETON
    UNIVERSITY MAKES NO REPRESENTATIONS OR WARRANTIES, EXPRESS OR
    IMPLIED.  BY WAY OF EXAMPLE, BUT NOT LIMITATION, PRINCETON
    UNIVERSITY MAKES NO REPRESENTATIONS OR WARRANTIES OF MERCHANT-
    ABILITY OR FITNESS FOR ANY PARTICULAR PURPOSE OR THAT THE USE
    OF THE LICENSED SOFTWARE, DATABASE OR DOCUMENTATION WILL NOT
    INFRINGE ANY THIRD PARTY PATENTS, COPYRIGHTS, TRADEMARKS OR
    OTHER RIGHTS.

    The name of Princeton University or Princeton may not be used in
    advertising or publicity pertaining to distribution of the software
    and/or database.  Title to copyright in this software, database and
    any associated documentation shall at all times remain with
    Princeton University and LICENSEE agrees to preserve same.

## Services the app shows words and pictures from

These are not part of the app; what they return is shown as it comes, credited on screen:

- Wiktionary (https://en.wiktionary.org), whose definitions are available under the Creative Commons
  Attribution-ShareAlike 4.0 International License (https://creativecommons.org/licenses/by-sa/4.0/)
- dictionaryapi.dev (https://dictionaryapi.dev), which serves Wiktionary's definitions under their licence
- Open Library (https://openlibrary.org), for book covers you search for
- GitHub (https://github.com), where the app's builds and the voice pack are published

## Code derived from NovelLibrary

Parts of the chapter clean-up and title detection are derived from NovelLibrary
(https://github.com/gmathi/NovelLibrary), Copyright gmathi and contributors, Apache License, Version 2.0, and
have been modified. The files say so in a header comment.

## Licence texts

### The MIT License (jsoup)

The MIT License

Copyright (c) 2009-2026 Jonathan Hedley <https://jsoup.org/>

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

### The MIT License (ONNX Runtime, piper-phonemize, cppjieba, checker-qual)

The same terms as above, with each project's own copyright line as given in its section.

### Apache License, Version 2.0

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

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
