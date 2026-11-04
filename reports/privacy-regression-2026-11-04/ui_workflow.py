"""Reproducible UI journeys using only this run's public fixtures and app jobs."""
import json
import re
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
import device_qa as qa

ROOT=Path(__file__).resolve().parent
sys.stdout.reconfigure(encoding='utf-8')

def tap(x,y):
    qa.adb('shell','input','tap',x,y); time.sleep(.5)
def capture(name):
    qa.snapshot(name)
    if not (ROOT/(name+'.xml')).exists():
        time.sleep(1);qa.snapshot(name)
    return list(ET.parse(ROOT/(name+'.xml')).iter('node')) if (ROOT/(name+'.xml')).exists() else []
def label(node): return node.get('text') or node.get('content-desc') or ''
def click(nodes, text, exact=False, right_edge=False):
    matches=[n for n in nodes if label(n)==text or (not exact and text in label(n))]
    assert matches, text
    n=matches[0]; left,top,right,bottom=map(int,re.findall(r'\d+',n.get('bounds')))
    tap(right-20 if right_edge else (left+right)//2,(top+bottom)//2)
def import_fixture(fragment,tag):
    tap(540,1750);time.sleep(1)
    nodes=capture(tag+'-picker')
    for attempt in range(8):
        if any(fragment in label(n) for n in nodes): break
        qa.adb('shell','input','swipe',540,2100,540,900,600);time.sleep(1)
        nodes=capture(tag+f'-picker-scroll-{attempt}')
    click(nodes,fragment)
    tap(870,2240);time.sleep(5)
    return capture(tag+'-identified')
def records():
    return json.loads(qa.adb('shell','run-as','art.gaoge.dance','cat','files/export_jobs.json').stdout)
def record(tag,job):
    p=ROOT/'ui-lifecycle.json';rows=json.loads(p.read_text()) if p.exists() else []
    rows.append({'tag':tag,'pid':qa.adb('shell','pidof','art.gaoge.dance').stdout.decode().strip(),'job':job})
    p.write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
def start_export(tag):
    before={r['jobId'] for r in records()}
    tap(985,187)
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        added=[r for r in records() if r['jobId'] not in before]
        if added:
            job=max(added,key=lambda r:r['createdAt']);record(tag+'-started',job)
            capture(tag+'-progress');return job['jobId']
        time.sleep(1)
    capture(tag+'-start-failed');raise AssertionError('No new native export job')
def wait_terminal(job_id,expected='completed'):
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        job=next(r for r in records() if r['jobId']==job_id)
        if job['state'] in ('completed','failed','cancelled','interrupted'):
            assert job['state']==expected, job
            return job
        time.sleep(1)
    raise AssertionError('Export did not terminate')

action=sys.argv[1]
if action=='prepare_group':
    qa.adb('shell','am','start','-n','art.gaoge.dance/.MainActivity');time.sleep(3)
    capture('final-home-current')
    nodes=import_fixture('2021年7月28日 下午6:20:58','final-ui-group')
    assert any('人物 6' in label(n) for n in nodes), 'Occluded person missing'
    tap(675,2260);nodes=capture('final-ui-group-ratio')
    if not any('选择人物 7 为主角' in label(n) for n in nodes):
        click(nodes,'9:16',exact=True);nodes=capture('final-ui-group-pick-hero')
    click(nodes,'选择人物 7 为主角',right_edge=True)
    tap(135,2260);qa.adb('shell','input','swipe',996,1910,203,1910,700);time.sleep(1)
    nodes=capture('final-ui-group-trim');assert any('保留 3.0 秒' in label(n) for n in nodes)
    tap(400,2260);nodes=capture('final-ui-group-ready')
    assert any('主角：人物 7 · 保护 6 人' in label(n) for n in nodes)
    tap(945,2260);tap(948,1980);time.sleep(1);capture('final-ui-group-strength')
elif action=='prepare_solo':
    nodes=import_fixture('2021年4月20日 上午12:35:06','final-ui-solo-4k')
    tap(675,2260);nodes=capture('final-ui-solo-frame');click(nodes,'原始',exact=True)
    tap(400,2260);time.sleep(2);nodes=capture('final-ui-solo-preview')
    assert not any('暂时无法更新' in label(n) for n in nodes)
    record('solo-preview-after-first-export',{})
elif action=='cancel':
    tag='final-ui-cancel';job_id=start_export(tag)
    time.sleep(2);qa.adb('shell','input','keyevent',3);time.sleep(2)
    (ROOT/'final-ui-background-service.txt').write_bytes(qa.adb('shell','dumpsys','activity','services','art.gaoge.dance').stdout)
    qa.adb('shell','am','start','-n','art.gaoge.dance/.MainActivity');time.sleep(2)
    capture(tag+'-returned');tap(540,2000)
    nodes=capture(tag+'-continue-dialog');click(nodes,'继续处理',exact=True)
    time.sleep(2);tap(540,2000)
    nodes=capture(tag+'-confirm-dialog');click(nodes,'取消处理',exact=True)
    job=wait_terminal(job_id,'cancelled');time.sleep(3)
    record(tag+'-cancelled',job);capture(tag+'-editor')
elif action=='prepare_retry':
    tap(135,2260);qa.adb('shell','input','swipe',996,1910,187,1910,700);time.sleep(1)
    qa.adb('shell','input','swipe',84,1910,120,1910,700);time.sleep(5)
    nodes=capture('final-ui-retry-trim')
    tap(400,2260);nodes=capture('final-ui-retry-review')
    for n in nodes:
        if label(n) == '确认保护' or ('确认保护对象' in label(n) and '请确认' not in label(n)):
            click(nodes,label(n),exact=True);break
    time.sleep(2);capture('final-ui-retry-ready')
elif action=='custom':
    nodes=capture('final-custom-current')
    if any('人物 1，未保护' in label(n) for n in nodes):
        click(nodes,'恢复默认选择',exact=True);time.sleep(1)
    if not any('遮挡样式' in label(n) for n in nodes): tap(400,2260)
    tap(705,1800)
    nodes=capture('final-custom-sheet');click(nodes,'导入自定义贴纸',exact=True)
    nodes=capture('final-custom-picker')
    click(nodes,'2:11:12')
    time.sleep(1);nodes=capture('final-custom-selected')
    if any('添加' in label(n) for n in nodes):
        tap(870,2240);time.sleep(1);nodes=capture('final-custom-added')
    assert any('自定义贴纸，已选择' in label(n) for n in nodes)
    tap(950,1745);tap(945,2260);tap(948,1980);tap(400,2260)
    capture('final-custom-preview');record('custom-sticker-imported',{})
elif action=='flower':
    before={r['jobId'] for r in records()}
    nodes=import_fixture('2018年3月7日 下午11:21:21','final-flower')
    assert any('没有找到可保护的人物' in label(n) for n in nodes)
    click(nodes,'重新识别',exact=True);time.sleep(3)
    nodes=capture('final-no-person-retry')
    assert any('没有找到可保护的人物' in label(n) for n in nodes)
    tap(985,187);nodes=capture('final-no-person-export-blocked')
    assert {r['jobId'] for r in records()}==before
    (ROOT/'ui-negative-results.json').write_text(json.dumps([
        {'case':'no-person-import-retry-export-blocked','result':'passed','no_new_export_job':True}
    ],indent=2))
elif action=='export':
    tag=sys.argv[2];job_id=start_export(tag);job=wait_terminal(job_id);time.sleep(2)
    record(tag+'-completed',job);nodes=capture(tag+'-result')
    assert any('已保存至系统相册' in label(n) for n in nodes)
    qa.jobs()
elif action=='collect':
    tag=sys.argv[2];job=max(records(),key=lambda r:r['createdAt'])
    job=wait_terminal(job['jobId']);time.sleep(2);record(tag+'-completed',job)
    nodes=capture(tag+'-result');assert any('已保存至系统相册' in label(n) for n in nodes)
    qa.jobs()
elif action=='interrupt':
    initial=set(qa.adb('shell','run-as','art.gaoge.dance','ls','cache/exports').stdout.decode().split())
    job_id=start_export('final-ui-interruption');time.sleep(2)
    before=next(r for r in records() if r['jobId']==job_id)
    assert before['state'] in ('preparing','processing','muxing'), before
    (ROOT/'interruption-before.json').write_text(json.dumps(before,indent=2),encoding='utf-8')
    qa.adb('shell','am','force-stop','art.gaoge.dance');time.sleep(1)
    qa.adb('shell','am','start','-n','art.gaoge.dance/.MainActivity');time.sleep(4)
    after=next(r for r in records() if r['jobId']==job_id)
    assert after['state']=='interrupted' and after['errorCode']=='EXPORT_INTERRUPTED' and after['outputUri'] is None, after
    (ROOT/'interruption-after.json').write_text(json.dumps(after,indent=2),encoding='utf-8')
    names=set(qa.adb('shell','run-as','art.gaoge.dance','ls','cache/exports').stdout.decode().split())
    own_temps=[name for name in names-initial if re.fullmatch(r'export_[0-9]+\.tmp\.mp4',name)]
    for name in own_temps:
        assert int(name.split('_')[1].split('.')[0])>=before['createdAt']
        qa.adb('shell','run-as','art.gaoge.dance','rm','cache/exports/'+name)
    (ROOT/'interruption-temp-cleanup.json').write_text(json.dumps({'job_id':job_id,'removed_only_this_test_temps':own_temps},indent=2))
    record('interrupted-after-explicit-process-stop',after);capture('final-ui-interrupted-home');qa.jobs()
elif action=='home':
    tap(110,187);time.sleep(1);capture('final-ui-return-home')
else: raise ValueError(action)
