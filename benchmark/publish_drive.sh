#!/usr/bin/env bash
# Mirror the project's documents and measurement results into the user's
# Google Drive folder, so they can be read from any device without the repo.
#
#   ./benchmark/publish_drive.sh
#
# The git working tree itself stays in ~/Projects: a .git directory inside a
# Drive-synced folder is a known source of corruption (partial syncs of
# index/refs), and the CI workflow, the scheduled weekly review and the JNI
# build all address the repo by its current path. Drive gets a read-only mirror
# of what a person would open: digests, reviews, the constraints file, the
# benchmark logs. Run after every commit that touches those — the weekly review
# task runs it itself.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${WIKI_LLM_DRIVE:-/Users/igormorozov/Library/CloudStorage/GoogleDrive-akozit@gmail.com/Мой диск/AI/LLM wiki}"

if [[ ! -d "$DEST" ]]; then
    echo "Drive folder not found: $DEST" >&2
    echo "(Drive not mounted, or the folder was moved — set WIKI_LLM_DRIVE)" >&2
    exit 1
fi

mkdir -p "$DEST/benchmark" "$DEST/docs"

# Top-level: what to read first.
cp "$HERE/CLAUDE.md"  "$DEST/CLAUDE.md"
cp "$HERE/README.md"  "$DEST/README.md"
cp "$HERE/benchmark/LATEST.md" "$DEST/LATEST.md"

# Weekly reviews (created by the Sunday task; absent until the first run).
[[ -d "$HERE/docs" ]] && rsync -a --delete "$HERE/docs/" "$DEST/docs/"

# Benchmark: the question set, scorers, every stored result. Exclude the raw
# diag.log dumps that are only ever read by the scorers — they are 256 KB
# ring buffers with no standalone meaning.
rsync -a --delete \
    --include='*.md' --include='*.txt' --include='*.json' --include='*.py' --include='*.sh' \
    --exclude='*' \
    "$HERE/benchmark/" "$DEST/benchmark/"

# Note what this mirror is, so the folder explains itself.
cat > "$DEST/_README.md" <<EOF
# Wiki-LLM — зеркало документов и замеров

Это копия документов проекта wiki-llm-android, обновляется скриптом
\`benchmark/publish_drive.sh\` из репозитория. Редактировать здесь бесполезно:
следующий запуск перезапишет. Источник — репозиторий на Mac
(~/Projects/wiki-llm-android) и GitHub akozit-del/wiki-llm-android.

- \`LATEST.md\` — дайджест прогонов, новые записи сверху. Читать первым.
- \`docs/weekly-review.md\` — недельные обзоры моделей и рантайма (по воскресеньям).
- \`CLAUDE.md\` — архитектура, ограничения NPU, текущее состояние, открытые дефекты.
- \`benchmark/\` — эталонные вопросы, скореры и все сохранённые результаты.

Обновлено: $(date '+%Y-%m-%d %H:%M %Z'), коммит $(git -C "$HERE" rev-parse --short HEAD).
EOF

echo "mirrored to: $DEST"
echo "commit: $(git -C "$HERE" rev-parse --short HEAD)"
