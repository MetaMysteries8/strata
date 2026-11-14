"""GPU-side export config for a trusted server-generated Nerfstudio checkpoint."""
import argparse
from pathlib import Path

def main():
    import yaml
    p=argparse.ArgumentParser();p.add_argument('config',type=Path);p.add_argument('checkpoint',type=Path);p.add_argument('output',type=Path);args=p.parse_args()
    # Only called with private server-generated paths selected by recovery.checkpoint.
    config=yaml.load(args.config.read_text(),Loader=yaml.Loader)
    # eval_setup in Nerfstudio 1.1.5 overwrites load_dir from get_checkpoint_dir.
    # An absolute relative_model_dir keeps that resolution on the saved snapshot.
    config.relative_model_dir=args.checkpoint.parent.resolve()
    config.load_dir=args.checkpoint.parent
    config.load_step=int(args.checkpoint.stem.split('-')[1])
    args.output.write_text(yaml.dump(config))

if __name__=='__main__':main()
