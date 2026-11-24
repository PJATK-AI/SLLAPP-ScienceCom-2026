# Blocked-word list attribution

`blocked-words.txt` contains 319 unique words adapted from the Polish (`pl`)
and English (`en`) lists in
[LDNOOBW](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words).

Copyright © 2012–2020 Shutterstock, Inc. and contributors.
The source is licensed under
[Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/).
The complete license and its warranty disclaimer are included in
[LICENSE-LDNOOBW.txt](LICENSE-LDNOOBW.txt). This license applies to the bundled
word list separately from the application's MIT license. No endorsement is implied.

Source revision: `5faf2ba42d7b1c0977169ec3611df25a3c08eb13`, retrieved 2026-11-17.

| File | Pinned source | Original SHA-256 |
| --- | --- | --- |
| Polish | [pl](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/blob/5faf2ba42d7b1c0977169ec3611df25a3c08eb13/pl) | `98cf824e3692255645c5a72d5913f26fd4f20930f725683b4c96bb312ca5d9b3` |
| English | [en](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/blob/5faf2ba42d7b1c0977169ec3611df25a3c08eb13/en) | `af851ecef1d5f212caba17339b12ac39cc2fef7d78c74876f67237644fcee8bd` |

Changes: retained single words made of Unicode letters and combining marks
(44 Polish and 275 English entries), normalized with NFKC and lowercase,
merged, deduplicated and sorted. Multiword phrases and entries containing
digits or punctuation were excluded, not split into separate words.
The source covers profanity, insults and sexual terms. It is not an exhaustive
list of offensive nicknames or Polish inflections.

The project adds entries for the number 67 and related Polish and English
nicknames.

The application loads this local copy at startup; it does not download updates.
