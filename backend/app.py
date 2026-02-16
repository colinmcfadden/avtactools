from flask import Flask
from flask_cors import CORS

# Import your Blueprints
from routes.terrain_routes import terrain_bp
from routes.location_routes import location_bp
from routes.weather_routes import weather_bp
from routes.export_routes import export_bp

app = Flask(__name__)
CORS(app)

# Register Blueprints
app.register_blueprint(export_bp)
app.register_blueprint(terrain_bp)
app.register_blueprint(location_bp)
app.register_blueprint(weather_bp)

@app.route('/')
def health_check():
    return {
        "status": "online",
        "service": "AvTacTools Backend",
        "version": "1.0"
    }

if __name__ == '__main__':
    app.run(debug=True, port=5000)