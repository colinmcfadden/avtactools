"""The HTTP layer: one blueprint per area of the API."""


def register_blueprints(app):
    # Imported here, not at the top, so that importing one route module for a
    # test does not load every other one (and the libraries behind them).
    from app.routes.admin import admin_bp
    from app.routes.aircraft import aircraft_bp
    from app.routes.auth import auth_bp
    from app.routes.export import export_bp
    from app.routes.health import health_bp
    from app.routes.lidar import lidar_bp
    from app.routes.location import location_bp
    from app.routes.lz import lz_bp
    from app.routes.point_sets import point_sets_bp
    from app.routes.route_share import route_share_bp
    from app.routes.saved_routes import saved_routes_bp
    from app.routes.terrain import terrain_bp
    from app.routes.threats import threat_bp
    from app.routes.weather import weather_bp

    for blueprint in (
        export_bp, terrain_bp, lidar_bp, location_bp, weather_bp, auth_bp, lz_bp,
        saved_routes_bp, point_sets_bp, threat_bp, route_share_bp, aircraft_bp,
        admin_bp, health_bp,
    ):
        app.register_blueprint(blueprint)
