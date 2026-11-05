# -*- coding: utf-8 -*-
import json, re

# ---------- 1. update.json note (preserve CRLF) ----------
p = 'update.json'
raw = open(p, 'rb').read()
d = json.loads(raw.decode('utf-8'))
note = d['note']
marker = 'v1.0.0.62 吃药日历新增「吃药记录」💊：'
idx = note.find(marker)
assert idx != -1, 'update.json marker not found'
new_para = ('v1.0.0.62 吃药日历「吃药记录」升级💊：记录从「每次精确到秒」改成「按天显示吃了几次」'
            '——日历页最下方按日期分组，每组直接显示「M月D日（周X）：已吃药 N 次」，一眼看清每天吃了几顿；'
            '点某天的「×」可撤销当天。重复记录有保护：今天记的次数到达「每天次数」上限后，再点「记一笔吃药（现在）」'
            '或「今天已吃药」会弹同款玻璃提示框「今天已吃药了，不要重复吃药哦！」（可选「仍要记录」/「取消」），'
            '避免不小心吃重。同一天该吃几顿就记几次，按钮与提示框风格都和其它一模一样。版本号仍为 v1.0.0.62。')
d['note'] = note[:idx] + new_para
out = json.dumps(d, ensure_ascii=False, separators=(',', ':'))
out = out.replace('\r\n', '\n').replace('\n', '\r\n')
if not out.endswith('\r\n'):
    out += '\r\n'
open(p, 'wb').write(out.encode('utf-8'))
d2 = json.loads(open(p, 'rb').read().decode('utf-8'))
assert '按天显示吃了几次' in d2['note']
assert '今天已吃药了，不要重复吃药哦！' in d2['note']
assert d2['versionName'] == '1.0.0.62' and d2['versionCode'] == 190
print('[1] update.json note updated')

# ---------- 2. UpdateManager.kt CHANGELOG head (v1.0.0.62 / 2026-10-14) ----------
p = 'app/src/main/java/com/baigao/countdown/UpdateManager.kt'
s = open(p, 'r', encoding='utf-8').read()
new = ('吃药日历「吃药记录」升级啦 💊 —— 记录从「每次精确到秒」改成「按天显示吃了几次」：'
       '日历页最下方按日期分组，每组直接写「M月D日（周X）：已吃药 N 次」，一眼看清每天吃了几顿；'
       '要撤销某天就点那天的「×」。重复记录有保护：当你今天记的次数已经到达「每天次数」设定上限，'
       '再点「记一笔吃药（现在）」或「今天已吃药」就会弹同款玻璃提示框「今天已吃药了，不要重复吃药哦！」'
       '（可选「仍要记录」或「取消」），避免不小心吃重。同一天该吃几顿就记几次，'
       '按钮与提示框风格都和其它一模一样，版本号仍为 v1.0.0.62 💙')
s2 = re.sub(r'ChangelogItem\("v1\.0\.0\.62", "2026-10-14", ".*?"\),',
            'ChangelogItem("v1.0.0.62", "2026-10-14", "%s"),' % new, s, count=1)
assert s2 != s, 'CHANGELOG not changed'
open(p, 'w', encoding='utf-8').write(s2)
print('[2] UpdateManager.kt CHANGELOG updated')

# ---------- 3. sync_to_github.py note_line ----------
p = 'sync_to_github.py'
s = open(p, 'r', encoding='utf-8').read()
new_med = ('💊 吃药日历「吃药记录」升级为「按天显示吃了几次」：日历页最下方按日期分组，'
           '每组显示「M月D日（周X）：已吃药 N 次」，一眼看清每天吃了几顿；点某天的「×」可撤销当天。'
           '重复记录有保护：今天记的次数到达「每天次数」上限后，再点「记一笔吃药（现在）」或「今天已吃药」'
           '会弹同款玻璃提示框「今天已吃药了，不要重复吃药哦！」（可选「仍要记录」/「取消」）。版本 v1.0.0.62。\n\n')
pat = re.compile(r'💊 吃药日历.*?版本 v1\.0\.0\.62。\\n\\n')
s2, n = pat.subn(new_med, s, count=1)
assert n == 1, 'sync note not replaced: %d' % n
open(p, 'w', encoding='utf-8').write(s2)
print('[3] sync_to_github.py note_line updated')
