# SLLAPP offline na Windows

Potrzebujesz działającego Dockera z Compose w trybie kontenerów linuksowych,
przeglądarki i kamery. Wybierz ZIP zgodny z procesorem stanowiska. Wydanie
z końcówką `amd64` jest dla komputerów z procesorem Intel lub AMD x64.

1. Pobierz ZIP z wydania SLLAPP i przenieś go na stanowisko.
2. Rozpakuj **całą paczkę** na dysku komputera. Uruchom Docker Desktop.
3. Kliknij dwukrotnie `Uruchom-gre.bat`. Pierwszy start trwa dłużej, bo
   skrypt importuje obrazy z dysku. Gra otworzy się pod
   `http://127.0.0.1:8088`. Zezwól przeglądarce na dostęp do kamery.

## Pliki obok tej instrukcji

| Plik | Kiedy go użyć i co robi |
| --- | --- |
| `Uruchom-gre.bat` | Uruchamia grę. Wczytuje brakujące obrazy z paczki, czeka na gotowość usług i otwiera przeglądarkę. |
| `Zatrzymaj-gre.bat` | Zatrzymuje grę po zakończeniu pracy. Zachowuje wyniki. |
| `Zastosuj-ustawienia.bat` | Po zapisaniu zmian w YAML-u odtwarza tylko backend. Zakończ rundę przed kliknięciem. Po zastosowaniu ustawień rozpocznij nową sesję. Ranking zostaje. |
| `Sprawdz-stan.bat` | Pokazuje stan usług i ostatnie logi. Użyj, gdy gra się nie otwiera albo ustawienia się nie wczytały. |
| `Wyczysc-ranking.bat` | Kończy działanie gry i trwale usuwa zapisane wyniki tej instalacji offline. Wpisz dokładnie `RESET`, aby potwierdzić. Enter bez wpisywania anulowuje. Następny start tworzy pusty ranking. |
| `offline/application.yml` | Plik do edycji w Notatniku. Reguluje między innymi długość rundy, punktację i próg rozpoznawania. |
| `offline/application.default.yml` | Kopia ustawień fabrycznych z tej paczki. Użyj jej do przywrócenia konfiguracji. |

Pliki `compose.offline.yaml`, `offline/images.env`, archiwum obrazów
`offline/images.tar.gz`, `model/artifacts/` i skrypt `Obsluga-offline.ps1`
są potrzebne do działania paczki. Zostaw je w dostarczonych lokalizacjach.

## Zmiana i przywracanie ustawień

Aby skrócić rundę do 30 sekund, otwórz `offline/application.yml` i zmień
`game-duration: 60s` na `game-duration: 30s`, zachowując wcięcie. Zapisz
plik pod tą samą nazwą, zakończ bieżącą rundę i kliknij
`Zastosuj-ustawienia.bat`. Rozpocznij nową sesję.

Aby wrócić do ustawień fabrycznych, skopiuj zawartość
`offline/application.default.yml` do `offline/application.yml`, zapisz
i zastosuj ustawienia ponownie.

## Gdy gra się nie uruchamia

- **Docker nie działa** — uruchom Docker Desktop, poczekaj na gotowość
  silnika i kliknij ponownie `Uruchom-gre.bat`.
- **Brakuje pliku lub import obrazu się nie udał** — sprawdź wolne miejsce
  i rozpakuj pełny ZIP ponownie. Upewnij się, że paczka pasuje do procesora.
- **Port 8088 jest zajęty** — zatrzymaj program lub inną instancję gry
  zajmującą ten port i spróbuj ponownie.
- **Backend nie wstaje po edycji YAML-a** — otwórz `Sprawdz-stan.bat` i sprawdź
  log backendu. Przywróć ustawienia fabryczne, a następnie zastosuj je.
- **Kamera się nie otwiera** — sprawdź jej podłączenie oraz zezwolenie
  dla gry w przeglądarce.

## Aktualizacja

Po rundzie użyj `Zatrzymaj-gre.bat`. Rozpakuj nowy ZIP do osobnego katalogu
i uruchom grę z nowej paczki. Ranking zostanie zachowany na tej samej
instalacji Dockera. Przenieś potrzebne wartości do nowego
`offline/application.yml`, jeśli zmieniałeś ustawienia na stoisku.
