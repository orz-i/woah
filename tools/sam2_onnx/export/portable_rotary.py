import torch
from contextlib import contextmanager

def reshape_for_broadcast(freqs_cis: torch.Tensor, x: torch.Tensor):
    ndim = x.ndim
    assert 0 <= 1 < ndim
    # freqs_cis shape is (Sq, D/2) or (Sq, D/2, 2)
    assert freqs_cis.shape[0] == x.shape[-2], f'{freqs_cis.shape} != {x.shape}'
    shape = [d if i >= ndim - 2 else 1 for i, d in enumerate(x.shape)]
    return freqs_cis.view(*shape)

def portable_apply_rotary_enc(
    xq: torch.Tensor,
    xk: torch.Tensor,
    freqs_cis: torch.Tensor,
    repeat_freqs_k: bool = False,
):
    # freqs_cis shape: (Sq, D/2) complex or (Sq, D/2, 2) real
    if torch.is_complex(freqs_cis):
        cos = freqs_cis.real.float()
        sin = freqs_cis.imag.float()
    else:
        cos = freqs_cis[..., 0].float()
        sin = freqs_cis[..., 1].float()

    xq_r = xq.float().reshape(*xq.shape[:-1], -1, 2)
    q_a = xq_r[..., 0]  # (B, H, Sq, D/2)
    q_b = xq_r[..., 1]  # (B, H, Sq, D/2)

    cos_q = reshape_for_broadcast(cos, q_a)
    sin_q = reshape_for_broadcast(sin, q_a)

    q_out_real = q_a * cos_q - q_b * sin_q
    q_out_imag = q_a * sin_q + q_b * cos_q
    xq_out = torch.stack([q_out_real, q_out_imag], dim=-1).flatten(3)

    if xk.shape[-2] == 0:
        return xq_out.type_as(xq), xk

    xk_r = xk.float().reshape(*xk.shape[:-1], -1, 2)
    k_a = xk_r[..., 0]  # (B, H, Sk, D/2)
    k_b = xk_r[..., 1]  # (B, H, Sk, D/2)

    cos_k = cos_q
    sin_k = sin_q
    if repeat_freqs_k:
        r = k_a.shape[-2] // q_a.shape[-2]
        if r > 1:
            cos_k = cos_q.unsqueeze(2).expand(-1, -1, r, -1, -1).flatten(2, 3)
            sin_k = sin_q.unsqueeze(2).expand(-1, -1, r, -1, -1).flatten(2, 3)

    k_out_real = k_a * cos_k - k_b * sin_k
    k_out_imag = k_a * sin_k + k_b * cos_k
    xk_out = torch.stack([k_out_real, k_out_imag], dim=-1).flatten(3)

    return xq_out.type_as(xq), xk_out.type_as(xk)

@contextmanager
def patch_rotary_for_export(model=None):
    import sam2.modeling.position_encoding as pos_enc_mod
    import sam2.modeling.sam.transformer as transformer_mod
    
    orig_pos_fn = pos_enc_mod.apply_rotary_enc
    orig_trans_fn = getattr(transformer_mod, 'apply_rotary_enc', None)
    
    pos_enc_mod.apply_rotary_enc = portable_apply_rotary_enc
    transformer_mod.apply_rotary_enc = portable_apply_rotary_enc
    
    backup_freqs = {}
    if model is not None:
        for name, mod in model.named_modules():
            if hasattr(mod, 'freqs_cis'):
                fc = getattr(mod, 'freqs_cis')
                if torch.is_tensor(fc) and torch.is_complex(fc):
                    backup_freqs[name] = fc
                    fc_real = torch.stack([fc.real.float(), fc.imag.float()], dim=-1)
                    setattr(mod, 'freqs_cis', fc_real)
    try:
        yield
    finally:
        pos_enc_mod.apply_rotary_enc = orig_pos_fn
        if orig_trans_fn is not None:
            transformer_mod.apply_rotary_enc = orig_trans_fn
        if model is not None:
            for name, fc in backup_freqs.items():
                target = model
                for part in name.split('.'):
                    target = getattr(target, part)
                setattr(target, 'freqs_cis', fc)
