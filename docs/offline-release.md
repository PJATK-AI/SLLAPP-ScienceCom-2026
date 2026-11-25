# Budowa i publikacja paczki offline

Po udanych testach backendu, frontendu i modelu na `master` zadanie
`offline-package` w GitHub Actions buduje obrazy dla `linux/amd64`
i składa jeden gotowy ZIP. Artefakt jest przechowywany przez 7 dni.
[Artefakty GitHub Actions](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)

## Pobranie i odbiór

1. W **Actions → CI** otwórz udane uruchomienie na `master` dla wybranego
   commita. Pobierz artefakt `sllapp-offline-<SHA>-amd64`.
2. Rozpakuj opakowanie artefaktu z Actions. W środku znajdziesz właściwy
   ZIP `sllapp-offline-<tag>.zip`. Porównaj jego SHA-256 z wartością z logu
   zadania. Na Windows użyj `Get-FileHash <plik.zip> -Algorithm SHA256`.
3. Sprawdź w `offline/images.env` po rozpakowaniu, czy SHA i platforma są
   zgodne z uruchomieniem CI. Plik zawiera również tagi oraz rozmiar i
   SHA-256 wewnętrznego archiwum `offline/images.tar.gz`.
4. Wykonaj próbę według `README.md` z paczki na czystym Windowsie z
   odciętym internetem i pustym magazynem obrazów. Zagraj rundę z kamerą.
   Sprawdź ranking po zatrzymaniu i ponownym starcie. Zmień czas rundy
   na 30 sekund i zastosuj ustawienia. Sprawdź anulowanie i potwierdzenie
   resetu na testowej bazie.

## Publikacja na GitHubie

Utwórz szkic wydania z nowym tagiem wskazującym pełny SHA z metadanych.
Podaj ścieżkę do **tego samego ZIP-a**, który przeszedł odbiór.
[GitHub CLI](https://cli.github.com/manual/gh_release_create)

```sh
gh release create '<tag-wydania>' '<ścieżka-do-ZIP-a>' \
  --repo PJATK-AI/SLLAPP-ScienceCom-2026 --target '<pełny-SHA>' --draft
```

W szkicu podaj platformę, rozmiar, sumę kontrolną i potrzebne miejsce po
imporcie. Opublikuj go i sprawdź, czy plik jest dostępny do pobrania. Przy
aktualizacji przygotuj nowe wydanie dla kolejnego przetestowanego ZIP-a.

Jeśli ZIP ma co najmniej 2 GiB, udostępnij go na dysku zespołu, a w wydaniu
podaj link i sumę SHA-256. [Limit pliku wydania](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases#storage-and-bandwidth-quotas)

## Ręczna budowa

Na komputerze z internetem przygotuj czysty checkout wybranego commita
`master`. Potrzebujesz Dockera z Buildx i Compose oraz Bash, `gzip`, `zip`,
`unzip` i `shasum`. Podaj pełny SHA tego checkoutu i platformę stanowiska.

```sh
bash scripts/package-offline.sh <pełny-SHA> linux/amd64 dist/offline
```

Dla stanowiska ARM64 użyj `linux/arm64` i buildera, który obsługuje tę
platformę. Skrypt wypisze SHA-256 i rozmiar gotowego ZIP-a. Ponowną budowę
sprawdź przed publikacją.
