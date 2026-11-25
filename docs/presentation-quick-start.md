# SLLAPP na pokazie

Na stanowisku bez internetu użyj gotowej paczki ZIP i pliku
`Uruchom-gre.bat`. Instrukcję znajdziesz w `README.md` po rozpakowaniu.
[Obsługa paczki offline](offline-quick-start.md) · [Przygotowanie wydania](offline-release.md)

## Start z repozytorium

Uruchamiaj polecenia z głównego katalogu projektu.

| Polecenie | Co robi |
| --- | --- |
| `docker compose up -d` | Uruchamia grę w tle. [Otwórz grę](http://127.0.0.1:8088) |
| `docker compose stop` | Zatrzymuje grę i zachowuje kontenery oraz ranking. |
| `docker compose down -v` | Zatrzymuje grę i **usuwa zapisany ranking**. |
| `docker compose up -d --force-recreate --no-deps backend` | Odtwarza sam backend po zmianie konfiguracji między rundami. Ranking zostaje. [Konfiguracja](../backend/src/main/resources/application.yml) |
| `docker compose up -d --build` | Przebudowuje obrazy po zmianie kodu i uruchamia grę. |
| `docker compose ps -a` | Pokazuje stan wszystkich kontenerów gry. |
| `docker compose logs --tail=50` | Pokazuje ostatnie 50 wierszy logów każdej usługi. |
