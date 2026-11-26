import json, os, plistlib, re, sys, xml.etree.ElementTree as ET
root = sys.argv[1]
ios = os.path.join(root, 'ios')
base = os.path.join(ios, 'SigninHub')
pbxpath = os.path.join(ios, 'SigninHub.xcodeproj', 'project.pbxproj')
errors = []

# ── Info.plist ────────────────────────────────────────────────
try:
    info = plistlib.load(open(os.path.join(base, 'Info.plist'), 'rb'))
except Exception as e:
    errors.append('Info.plist 解析失败: %s' % e); info = {}

# ── xcscheme（根元素是 <Scheme>，不是 <plist>，不能用 plistlib）──
scheme_path = os.path.join(ios, 'SigninHub.xcodeproj/xcshareddata/xcschemes/SigninHub.xcscheme')
try:
    tree = ET.parse(scheme_path)
    rootel = tree.getroot()
    if rootel.tag != 'Scheme':
        errors.append('xcscheme 根元素不是 <Scheme>，是 <%s>' % rootel.tag)
except Exception as e:
    errors.append('xcscheme 解析失败: %s' % e)

pbx = open(pbxpath, encoding='utf-8').read()

# ── pbxproj 对象表：按行首 8 位 hex 抓定义，不跨行吞对象 ──
# 旧正则 r'(\w{8}) /\* .*? \*/ = \{isa = PBXFileReference;(.*?)\};' 有两个毛病：
#   1. .*? 跨行时会把上一个 PBXBuildFile 连同注释一起吃进来（误入 A1000001）
#   2. 多行定义的 PBXFileReference（isa 与 = { 不同行）完全抓不到（漏 A2000001/A4000005）
# 改成先按「行首 2 tab + 8 位 id + = {」切块，再在块内看 isa。
def objects(src):
    """按行首「2 tab + 8 位 ID + = {」切分对象块。

    不能用正则一次匹配 {"..."}; —— 单行对象（A1000001）和块对象（A3000001）
    混在一起时，贪婪/非贪婪都会跨块吞掉相邻对象：
      · 非贪婪 .*? 从单行对象开始，会一路吞到下一个块对象的结尾
    正确做法是先按行定位每个对象起点，再按其后的第一个「行首 2 tab + };」收尾。
    """
    lines = src.split('\n')
    starts = []
    pat = re.compile(r'^\t\t([A-F0-9]{8})(?: /\*.*?\*/)? = \{')
    for idx, ln in enumerate(lines):
        m = pat.match(ln)
        if m:
            starts.append((idx, m.group(1), ln))
    out = {}
    for k, (idx, oid, opening) in enumerate(starts):
        # 单行对象：'{' 与 '};' 同一行
        if '};' in opening[opening.index('= {'):]:
            out[oid] = opening
            continue
        # 块对象：找到下一个「行首 2 tab + };」
        body = []
        for j in range(idx + 1, len(lines)):
            if lines[j] == '\t\t};' or lines[j].startswith('\t\t};'):
                break
            body.append(lines[j])
        out[oid] = '\n'.join(body)
    return out

objs = objects(pbx)
if not objs:
    errors.append('pbxproj 解析出 0 个对象，格式可能已变')

file_refs = {k: v for k, v in objs.items() if re.search(r'\bisa = PBXFileReference;', v)}

# 引用完整性：所有 8 位 hex 引用都必须有定义（rootObject 除外）
defined = set(objs)
referenced = set(re.findall(r'\b([A-F0-9]{8})\b', pbx))
dangling = sorted(x for x in referenced - defined)
if dangling:
    errors.append('pbxproj 有悬空引用: %s' % ', '.join(dangling))

# 每条 PBXFileReference 的 path 必须落盘
skip_trees = {'BUILT_PRODUCTS_DIR', 'SDKROOT', 'DEVELOPER_DIR'}
checked = 0
for oid, body in file_refs.items():
    pm = re.search(r'path = "?([^";]+)"?;', body)
    if not pm:
        continue
    path = pm.group(1)
    st = re.search(r"sourceTree = \"?([^\";]+)\"?;", body)
    tree = st.group(1) if st else '<group>'
    if tree in skip_trees:
        continue
    full = os.path.normpath(os.path.join(ios if tree == 'SOURCE_ROOT' else base, path))
    checked += 1
    if not os.path.exists(full):
        errors.append('pbxproj 引用了不存在的路径: %s (sourceTree=%s)' % (path, tree))
if checked < len(file_refs) - 1:
    errors.append('pbxproj 只检查了 %d / %d 条 PBXFileReference，解析可能漏项'
                  % (checked, len(file_refs)))

# Sources phase 里登记的 swift 文件，必须都在 file_refs 里且有磁盘路径
src_phase = re.search(r'isa = PBXSourcesBuildPhase;(.*?)^\t\t\};', pbx, re.S | re.M)
if not src_phase:
    errors.append('找不到 PBXSourcesBuildPhase')
else:
    for bid in re.findall(r'\b([A-F0-9]{8}) /\* [^*]+ in Sources \*/', src_phase.group(1)):
        bf = objs.get(bid)
        if not bf:
            errors.append('Sources phase 里的 %s 没有 PBXBuildFile 定义' % bid); continue
        fr = re.search(r'fileRef = ([A-F0-9]{8})', bf)
        if not fr or fr.group(1) not in defined:
            errors.append('PBXBuildFile %s 的 fileRef 悬空' % bid)

# Asset catalog 内部 JSON
assets = os.path.join(base, 'Assets.xcassets')
if not os.path.isdir(assets):
    errors.append('Assets.xcassets 目录不存在')
else:
    for dirpath, _, files in os.walk(assets):
        for f in files:
            if f.endswith('.json'):
                try:
                    json.load(open(os.path.join(dirpath, f)))
                except Exception as e:
                    errors.append('%s 非法 JSON: %s' % (f, e))

# AppIcon 的 Contents.json 必须指向一个真实存在的 png
try:
    icon = json.load(open(os.path.join(assets, 'AppIcon.appiconset', 'Contents.json')))
    for im in icon.get('images', []):
        fn = im.get('filename')
        if fn and not os.path.exists(os.path.join(assets, 'AppIcon.appiconset', fn)):
            errors.append('AppIcon 引用的 %s 不存在' % fn)
except Exception as e:
    errors.append('AppIcon Contents.json 读取失败: %s' % e)

# Info.plist 引用的启动色必须在 Assets 里存在
launch_color = (info.get('UILaunchScreen') or {}).get('UIColorName')
if launch_color and not os.path.isdir(os.path.join(assets, launch_color + '.colorset')):
    errors.append('UILaunchScreen 引用的颜色 %s 在 Assets 里不存在' % launch_color)

# BGTask id
bg_path = os.path.join(base, 'Background', 'BGSign.swift')
try:
    bg = open(bg_path, encoding='utf-8').read()
    swift_ids = set(re.findall(r'taskID = "([^"]+)"', bg))
    plist_ids = set(info.get('BGTaskSchedulerPermittedIdentifiers', []))
    if swift_ids != plist_ids:
        errors.append('BGTask id 不一致: swift=%s plist=%s' % (swift_ids, plist_ids))
    modes = set(info.get('UIBackgroundModes', []))
    if 'fetch' not in modes:
        errors.append('UIBackgroundModes 缺 fetch，BGAppRefreshTask 不会生效')
    if 'processing' in modes and 'BGProcessingTaskRequest' not in bg:
        errors.append('UIBackgroundModes 声明了 processing 但代码没注册 BGProcessingTask')
except Exception as e:
    errors.append('BGSign.swift 读取失败: %s' % e)

# Swift 语法粗筛：用不到编译器，但能抓住最常见的漏反斜杠
for dirpath, _, files in os.walk(base):
    for f in files:
        if not f.endswith('.swift'):
            continue
        src = open(os.path.join(dirpath, f), encoding='utf-8').read()
        for m in re.finditer(r'@(?:Environment|AppStorage|SceneStorage|FocusState|Binding)\(\s*\.', src):
            line = src[:m.start()].count('\n') + 1
            errors.append('%s:%d 属性包装器缺少反斜杠（应为 (\\.xxx)）' % (f, line))
        if src.count('{') != src.count('}'):
            errors.append('%s 花括号不平衡 %d vs %d' % (f, src.count('{'), src.count('}')))

print('--- 校验明细 ---')
print('  pbxproj 对象: %d 个，其中 PBXFileReference %d 条（落盘检查 %d 条）'
      % (len(objs), len(file_refs), checked))
print('  Info.plist 键: %d 个' % len(info))
if errors:
    print('工程校验失败：')
    for e in errors:
        print('  -', e)
    sys.exit(1)
print('工程校验通过：0 个错误')
