"""Bind the optional real-media cancellation/retry chain to its owned output."""
from __future__ import annotations
import hashlib,json
from pathlib import Path

def read(path):
    try:return json.loads(path.read_text(encoding='utf-8'))
    except (OSError,ValueError) as error:raise RuntimeError('Missing or invalid lifecycle evidence: '+path.name) from error

def required(condition,message):
    if not condition:raise RuntimeError(message)

def retry_case(run:Path):
    meta=read(run/'run-metadata.json')
    if not meta.get('config',{}).get('lifecycle'):return None
    path=run/'qa_lifecycle_results.json'
    if not path.exists():return None
    life=read(path)
    if not life.get('input'):return None
    status=life.get('retry_status') or {}
    return {'case':'retry_custom_sticker','audit_origin':'lifecycle_chain','input':life['input'],
        'fill':life['fill'],'start_ms':life['retry_start_ms'],'end_ms':life['retry_end_ms'],
        'face_only':life['face_only'],'output_width':life['target_width'],'output_height':life['target_height'],
        'selected_ids':life['selected_ids'],'hero_id':None,'expected_frames':status.get('current_frame',0),
        'result':'output_created' if status.get('state')=='completed' else 'failed',
        'error_code':status.get('error_code'),'error_message':status.get('error_message')}

def verify_lifecycle(run:Path):
    meta=read(run/'run-metadata.json')
    if not meta.get('config',{}).get('lifecycle'):return {'status':'not_requested'}
    api=read(run/'results.json');life=read(run/'qa_lifecycle_results.json');negative=read(run/'qa_negative_results.json')
    required(life.get('process_id')==api['process_id'] and negative.get('process_id')==api['process_id'],
             'Lifecycle evidence belongs to a different app process')
    required(life.get('status')=='passed_api_chain' and life.get('cancel_passed') is True and life.get('retry_passed') is True,
             'Native lifecycle chain failed')
    required((life.get('after_cancel') or {}).get('state')=='cancelled'
             and life.get('cancelled_output_exists') is False and life.get('cancelled_partial_files')==[],
             'Cancellation did not release all owned output files')
    required(life.get('custom_sticker_exists') is True,'Persisted custom sticker is missing')
    required(negative.get('status')=='passed_api_checks' and all(negative.get(k) is True for k in
             ['flower_no_person','corrupt_rejected','valid_reimport']),'Native negative/reimport checks failed')
    row=retry_case(run)
    required(row is not None and row['result']=='output_created','Lifecycle retry parameters or completed status missing')
    movie=run/'outputs/retry_custom_sticker.mp4'
    required(movie.is_file() and movie.stat().st_size==life.get('retry_bytes') and movie.stat().st_size>1024,
             'Lifecycle retry movie does not match the native byte count')
    return {'status':'passed_api_chain','retry_movie':'outputs/retry_custom_sticker.mp4',
        'retry_sha256':hashlib.sha256(movie.read_bytes()).hexdigest(),'retry_bytes':movie.stat().st_size,
        'negative_status':negative['status'],
        'scope':'API flow and owned file evidence. Independent media, face and visual audits remain required.'}
