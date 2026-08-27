import os
import sys
import onnx
from onnxruntime.quantization import quantize_dynamic, QuantType

def quantize_all():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
    gen_dir = os.path.join(root_dir, 'tools/sam2_onnx/.generated')
    
    models = [
        'sam2_image_features.onnx',
        'sam2_init_step.onnx',
        'sam2_temporal_step.onnx'
    ]
    
    print('[Quantize] Starting dynamic INT8 quantization for SAM2 models...')
    for m in models:
        src = os.path.join(gen_dir, m)
        if not os.path.exists(src):
            print(f'Error: Source model not found at {src}')
            continue
            
        temp_out = os.path.join(gen_dir, m.replace('.onnx', '_int8_temp.onnx'))
        print(f'[Quantize] Quantizing {m}...')
        quantize_dynamic(
            model_input=src,
            model_output=temp_out,
            weight_type=QuantType.QInt8,
            op_types_to_quantize=['MatMul', 'Gemm'],
            per_channel=True,
            reduce_range=True
        )

        
        src_size = os.path.getsize(src) / 1024 / 1024
        int8_size = os.path.getsize(temp_out) / 1024 / 1024
        print(f'[Quantize] {m}: {src_size:.2f} MB -> {int8_size:.2f} MB')
        
        # Replace target model with INT8 version
        target_path = os.path.join(gen_dir, m)
        backup_fp32 = os.path.join(gen_dir, m.replace('.onnx', '_fp32.onnx'))
        if not os.path.exists(backup_fp32):
            os.rename(src, backup_fp32)
        else:
            os.remove(src)
        os.rename(temp_out, target_path)
        print(f'[Quantize] Updated {target_path} to INT8!')

if __name__ == '__main__':
    quantize_all()
