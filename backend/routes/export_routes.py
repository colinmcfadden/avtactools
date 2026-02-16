from flask import Blueprint, request, send_file, jsonify, current_app
import zipfile
import os
import io

# CHANGED: Import the new function that handles Blue(Tiff) vs Red(Jpg) logic
from export_service import generate_custom_package

export_bp = Blueprint('export_bp', __name__)

@export_bp.route('/api/export-package', methods=['POST'])
def export_package():
    data = request.json
    
    # CHANGED: We now expect a dictionary like { "blue": [...], "red": [...] }
    # instead of a flat list.
    bounds_dict = data.get('bounds') 
    
    if not bounds_dict:
        return jsonify({"error": "No bounds provided"}), 400

    try:
        # 1. Generate the custom files (ForeFlight Tiff + Visual Jpg)
        # This function returns a list of file paths that were created on disk
        file_paths = generate_custom_package(bounds_dict)
        
        if not file_paths:
            return jsonify({"error": "No valid bounds received or file generation failed"}), 400

        # 2. Create a Zip file in memory
        memory_file = io.BytesIO()
        with zipfile.ZipFile(memory_file, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for f_path in file_paths:
                # Add the file to the zip with its generated name
                zipf.write(f_path, arcname=os.path.basename(f_path))
                
                # Clean up the temp file from disk immediately after adding to zip
                try:
                    os.remove(f_path)
                except Exception as cleanup_error:
                    current_app.logger.warning(f"Warning: Could not cleanup temp file {f_path}: {cleanup_error}")
            
            # Optional: Add metadata
            zipf.writestr("metadata.txt", f"Export Configuration: {bounds_dict}")

        # 3. Send back to client
        memory_file.seek(0)

        return send_file(
            memory_file,
            mimetype='application/zip',
            as_attachment=True,
            download_name='Mission_Package.zip'
        )

    except Exception as e:
        current_app.logger.error(f"Export failed: {str(e)}")
        # Clean up any files that might have been created before the error
        # (This is a safety check)
        if 'file_paths' in locals():
            for f in file_paths:
                if os.path.exists(f):
                    try: os.remove(f)
                    except: pass
                    
        return jsonify({"error": str(e)}), 500