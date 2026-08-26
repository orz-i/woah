import os
import sys
import json
import onnx

def get_tensor_info(val):
    name = val.name
    tensor_type = val.type.tensor_type
    elem_type = tensor_type.elem_type
    dtype_map = {
        1: 'FLOAT',
        2: 'UINT8',
        3: 'INT8',
        4: 'UINT16',
        5: 'INT16',
        6: 'INT32',
        7: 'INT64',
        8: 'STRING',
        9: 'BOOL',
        10: 'FLOAT16',
        11: 'DOUBLE',
        12: 'UINT32',
        13: 'UINT64',
        14: 'COMPLEX64',
        15: 'COMPLEX128',
        16: 'BFLOAT16'
    }
    dtype_str = dtype_map.get(elem_type, str(elem_type))
    
    shape = []
    for d in tensor_type.shape.dim:
        if d.dim_param:
            shape.append(d.dim_param)
        elif d.dim_value is not None:
            shape.append(d.dim_value)
        else:
            shape.append('unknown')
    return {
        'name': name,
        'dtype': dtype_str,
        'shape': shape
    }

def validate_all_models():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
    gen_dir = os.path.join(root_dir, 'tools/sam2_onnx/.generated')
    
    models = {
        'sam2_image_features': os.path.join(gen_dir, 'sam2_image_features.onnx'),
        'sam2_init_step': os.path.join(gen_dir, 'sam2_init_step.onnx'),
        'sam2_temporal_step': os.path.join(gen_dir, 'sam2_temporal_step.onnx')
    }
    
    contract = {}
    print('[Validate] Validating all exported ONNX models...')
    
    for model_name, model_path in models.items():
        if not os.path.exists(model_path):
            raise FileNotFoundError(f'Model not found: {model_path}')
            
        print(f'Checking {model_name}...')
        onnx_model = onnx.load(model_path)
        onnx.checker.check_model(onnx_model)
        
        opsets = {entry.domain if entry.domain else 'ai.onnx': entry.version for entry in onnx_model.opset_import}
        
        inputs = [get_tensor_info(i) for i in onnx_model.graph.input]
        outputs = [get_tensor_info(o) for o in onnx_model.graph.output]
        
        file_size_mb = os.path.getsize(model_path) / (1024 * 1024)
        
        contract[model_name] = {
            'file_name': os.path.basename(model_path),
            'size_mb': round(file_size_mb, 2),
            'opset_imports': opsets,
            'inputs': inputs,
            'outputs': outputs
        }
        print(f'  {model_name}: PASS ({file_size_mb:.2f} MB, {len(inputs)} inputs, {len(outputs)} outputs)')
        
    contract_path = os.path.abspath(os.path.join(os.path.dirname(__file__), '../tensor_contract.json'))
    with open(contract_path, 'w', encoding='utf-8') as f:
        json.dump(contract, f, indent=2)
        
    print(f'[Validate] SUCCESS: Wrote tensor contract to {contract_path}')
    print(json.dumps(contract, indent=2))

if __name__ == '__main__':
    validate_all_models()
