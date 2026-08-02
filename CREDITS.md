# Third-Party Credits

hushkeyboard is built using the following third-party components. This file satisfies the
attribution obligations logged in `DEFINITION_OF_RIGHT.md`'s Decision Log for each one.

**Status note:** this is the content of the obligation. Whether it is displayed inside the app (a
licences/credits screen) or only shipped as this repo file is a separate decision. Apache 2.0
and CC BY 3.0's attribution requirements are triggered by *distribution*, so this content must
be reachable by users when the app is distributed.

---

## SmolLM2-135M-Instruct

- **Author:** Hugging Face (the SmolLM team)
- **Licence:** Apache License, Version 2.0
- **Used for:** the on-device language model powering hushkeyboard's word prediction and
  next-word suggestions.

```
Copyright 2024 Hugging Face

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Word frequency data (`top_english_words_lower_50000.txt`)

- **Source:** `david47k/top-english-wordlists`, derived from the Google Books Ngrams corpus
  (1950–2012)
- **Licence:** Creative Commons Attribution 3.0 Unported (CC BY 3.0)
- **Used for:** the bundled English dictionary backing hushkeyboard's autocorrect.

> Word frequency data: Google Books Ngrams via david47k/top-english-wordlists, licensed under
> CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/).

## llama.cpp

- **Author:** the ggml-org / llama.cpp contributors
- **Licence:** MIT License
- **Used for:** the offline, on-device native inference engine that runs the language model above.
  Compiled directly into the app; makes no network calls.

```
MIT License

Copyright (c) 2023-2024 The ggml authors

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

---

## Not yet audited

This file covers the two dormant obligations explicitly logged in `DEFINITION_OF_RIGHT.md`, plus
llama.cpp as the core bundled native engine. It is **not** a full dependency-by-dependency licence
audit of every library in the project (e.g. AndroidX, Kotlin standard library) — those were each
checked against `DEFINITION_OF_RIGHT.md` Gate 6 when adopted, and carry standard, low-risk licences
(mostly Apache 2.0), but have not been compiled into a single attribution file.
