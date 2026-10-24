#!/bin/bash
# Lightweight sampler: every 4 min, log top processes; every 12 min, `sample`
# the hung java process for 5 s of stack. Written so a 90-min silent createIPA
# still yields *something* on cancel (runs #3/#4 went dark for 73/79 min).
OUT="$GITHUB_WORKSPACE/build-diagnostics"
mkdir -p "$OUT"
END=$((SECONDS + 88*60))
i=0
while [ $SECONDS -lt $END ]; do
  {
    echo "=== $(date -u +%H:%M:%S) ==="
    ps -Ao pid,pcpu,pmem,etime,comm -r | head -n 12
  } >> "$OUT/proc.log"
  if [ $((i % 3)) -eq 0 ]; then
    JP=$(pgrep -f 'GradleDaemon|robovm|clang' | head -n1)
    if [ -n "$JP" ]; then
      sample "$JP" 5 -mayDie -file "$OUT/sample_${i}.txt" >/dev/null 2>&1 || true
    fi
  fi
  i=$((i+1))
  sleep 240
done