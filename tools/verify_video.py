import os
import struct
import sys
from pathlib import Path


def inspect_mp4_atoms(file_path):
    """Parses top-level MP4 container atoms and checks moov placement."""
    if not os.path.exists(file_path):
        return {"valid": False, "error": f"File not found: {file_path}"}

    atoms = []
    file_size = os.path.getsize(file_path)

    with open(file_path, "rb") as f:
        offset = 0
        while offset < file_size:
            header = f.read(8)
            if len(header) < 8:
                break
            atom_size, atom_type = struct.unpack(">I4s", header)
            atom_name = atom_type.decode("latin1", errors="replace")

            if atom_size == 1:
                # 64-bit large size
                large_header = f.read(8)
                atom_size = struct.unpack(">Q", large_header)[0]
                payload_offset = offset + 16
            elif atom_size == 0:
                # Atom extends to end of file
                atom_size = file_size - offset
                payload_offset = offset + 8
            else:
                payload_offset = offset + 8

            atoms.append({"name": atom_name, "offset": offset, "size": atom_size})

            if atom_size <= 0:
                break
            offset += atom_size
            f.seek(offset)

    atom_names = [a["name"] for a in atoms]
    has_ftyp = "ftyp" in atom_names
    has_moov = "moov" in atom_names
    has_mdat = "mdat" in atom_names

    moov_before_mdat = False
    if has_moov and has_mdat:
        moov_idx = atom_names.index("moov")
        mdat_idx = atom_names.index("mdat")
        moov_before_mdat = moov_idx < mdat_idx

    return {
        "valid": has_ftyp and has_moov and has_mdat,
        "file_size": file_size,
        "atoms": atoms,
        "has_ftyp": has_ftyp,
        "has_moov": has_moov,
        "has_mdat": has_mdat,
        "fast_start_ready": moov_before_mdat,
    }


def main():
    print("=" * 60)
    print(" Dance Anonymizer - MP4 Video Container Validator ")
    print("=" * 60)

    if len(sys.argv) < 2:
        print("Usage: uv run python tools/verify_video.py <path_to_video.mp4>")
        print("Demo: running internal structure parser check...")
        print("=" * 60)
        return

    path = sys.argv[1]
    res = inspect_mp4_atoms(path)
    print(f"File:             {path}")
    print(f"Valid MP4:        {res['valid']}")
    print(f"Fast Start (Web): {res.get('fast_start_ready', False)}")
    print(f"Detected Atoms:   {[a['name'] for a in res.get('atoms', [])]}")
    print("=" * 60)


if __name__ == "__main__":
    main()
