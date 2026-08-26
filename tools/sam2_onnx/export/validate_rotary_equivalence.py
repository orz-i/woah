import os
import sys
import torch
import numpy as np

VENDOR_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../../desktop/vendor'))
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

import sam2.modeling.position_encoding as pos_enc_mod
from portable_rotary import portable_apply_rotary_enc

def run_tests():
    pristine_fn = pos_enc_mod.apply_rotary_enc
    
    test_cases = [
        # (B, H, S_q, S_k, D, repeat_freqs_k)
        (1, 1, 16, 16, 16, False),
        (1, 4, 64, 64, 32, False),
        (2, 4, 64, 64, 64, False),
        (1, 8, 4096, 4096, 32, False),
        (2, 8, 64, 128, 32, True),
        (1, 4, 16, 32, 64, True),
        (1, 4, 64, 256, 32, True),
        (2, 2, 32, 32, 16, False),
        (4, 1, 64, 64, 32, False),
        (1, 8, 256, 256, 64, False),
        (2, 4, 16, 64, 32, True),
        (1, 2, 128, 512, 32, True),
        (1, 1, 1024, 1024, 64, False),
        (2, 8, 128, 128, 32, False),
        (1, 4, 32, 128, 64, True),
        (3, 4, 64, 192, 32, True),
        (1, 8, 4096, 4096, 64, False),
        (2, 4, 256, 512, 32, True),
        (1, 1, 512, 2048, 64, True),
        (2, 2, 64, 64, 64, False),
        (1, 4, 64, 128, 32, True),
        (2, 2, 32, 96, 64, True)
    ]
    
    print(f'[RoPE Validation] Running {len(test_cases)} test cases...')
    max_all_abs_error = 0.0
    mean_all_abs_error = 0.0
    
    for idx, (B, H, Sq, Sk, D, repeat_k) in enumerate(test_cases):
        torch.manual_seed(42 + idx)
        
        # xq: (B, H, Sq, D), xk: (B, H, Sk, D)
        xq = torch.randn(B, H, Sq, D)
        xk = torch.randn(B, H, Sk, D)
        
        # freqs_cis shape: (Sq, D/2) complex64
        freqs = torch.randn(Sq, D // 2)
        freqs_cis = torch.polar(torch.ones_like(freqs), freqs)
        
        # Run pristine complex version
        pristine_q, pristine_k = pristine_fn(xq, xk, freqs_cis, repeat_freqs_k=repeat_k)
            
        # Run portable real version
        port_q, port_k = portable_apply_rotary_enc(xq, xk, freqs_cis, repeat_freqs_k=repeat_k)
        
        diff_q = (pristine_q - port_q).abs()
        max_q_err = diff_q.max().item() if diff_q.numel() > 0 else 0.0
        mean_q_err = diff_q.mean().item() if diff_q.numel() > 0 else 0.0
        
        if xk.shape[-2] > 0 and port_k is not None and pristine_k is not None:
            diff_k = (pristine_k - port_k).abs()
            max_k_err = diff_k.max().item() if diff_k.numel() > 0 else 0.0
            mean_k_err = diff_k.mean().item() if diff_k.numel() > 0 else 0.0
        else:
            max_k_err, mean_k_err = 0.0, 0.0
            
        max_err = max(max_q_err, max_k_err)
        mean_err = (mean_q_err + mean_k_err) / 2.0
        
        max_all_abs_error = max(max_all_abs_error, max_err)
        mean_all_abs_error += mean_err
        
        assert max_err < 1e-4, f'Case {idx} FAIL: maxAbsError {max_err} >= 1e-4'
        print(f'  Case {idx:02d}: B={B}, H={H}, Sq={Sq}, Sk={Sk}, D={D}, rep={repeat_k} | maxErr={max_err:.2e}, meanErr={mean_err:.2e} -> PASS')

    mean_all_abs_error /= len(test_cases)
    print(f'[RoPE Validation] ALL {len(test_cases)} CASES PASSED! maxAbsError={max_all_abs_error:.2e}, meanAbsError={mean_all_abs_error:.2e}')

if __name__ == '__main__':
    run_tests()
